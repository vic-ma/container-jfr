/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.net;

import java.net.MalformedURLException;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.remote.JMXServiceURL;

import io.cryostat.core.log.Logger;
import io.cryostat.core.net.Credentials;
import io.cryostat.core.net.JFRConnection;
import io.cryostat.core.net.JFRConnectionToolkit;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.Scheduler;
import dagger.Lazy;

public class TargetConnectionManager {

    public static final Pattern HOST_PORT_PAIR_PATTERN =
            Pattern.compile("^([^:\\s]+)(?::(\\d{1,5}))?$");

    static final Duration DEFAULT_TTL = Duration.ofSeconds(90);

    private final Lazy<JFRConnectionToolkit> jfrConnectionToolkit;
    private final Logger logger;

    private final LoadingCache<ConnectionDescriptor, JFRConnection> connections;

    TargetConnectionManager(
            Lazy<JFRConnectionToolkit> jfrConnectionToolkit, Duration ttl, Logger logger) {
        this.jfrConnectionToolkit = jfrConnectionToolkit;
        this.logger = logger;

        this.connections =
                Caffeine.newBuilder()
                        .scheduler(Scheduler.systemScheduler())
                        .expireAfterAccess(ttl)
                        .removalListener(
                                new RemovalListener<ConnectionDescriptor, JFRConnection>() {
                                    @Override
                                    public void onRemoval(
                                            ConnectionDescriptor descriptor,
                                            JFRConnection connection,
                                            RemovalCause cause) {
                                        if (descriptor == null) {
                                            logger.warn(
                                                    "Connection eviction triggered with null descriptor");
                                            return;
                                        }
                                        if (connection == null) {
                                            logger.warn(
                                                    "Connection eviction triggered with null connection");
                                            return;
                                        }
                                        logger.info(
                                                "Removing cached connection for {}",
                                                descriptor.getTargetId());
                                        connection.close();
                                    }
                                })
                        .build(this::connect);
    }

    public <T> T executeConnectedTask(
            ConnectionDescriptor connectionDescriptor, ConnectedTask<T> task) throws Exception {
        return task.execute(connections.get(connectionDescriptor));
    }

    /**
     * Mark a connection as still in use by the consumer. Connections expire from cache and are
     * automatically closed after {@link TargetConnectionManager.DEFAULT_TTL}. For long-running
     * operations which may hold the connection open and active for longer than the default TTL,
     * this method provides a way for the consumer to inform the {@link TargetConnectionManager} and
     * its internal cache that the connection is in fact still active and should not be
     * expired/closed. This will extend the lifetime of the cache entry by another TTL into the
     * future from the time this method is called. This may be done repeatedly as long as the
     * connection is required to remain active.
     *
     * @return false if the connection for the specified {@link ConnectionDescriptor} was already
     *     removed from cache, true if it is still active and was refreshed
     */
    public boolean markConnectionInUse(ConnectionDescriptor connectionDescriptor) {
        return connections.getIfPresent(connectionDescriptor) != null;
    }

    private JFRConnection connect(ConnectionDescriptor connectionDescriptor) throws Exception {
        try {
            return attemptConnectAsJMXServiceURL(connectionDescriptor);
        } catch (MalformedURLException mue) {
            return attemptConnectAsHostPortPair(connectionDescriptor);
        }
    }

    private JFRConnection attemptConnectAsJMXServiceURL(ConnectionDescriptor connectionDescriptor)
            throws Exception {
        return connect(
                connectionDescriptor,
                new JMXServiceURL(connectionDescriptor.getTargetId()),
                connectionDescriptor.getCredentials());
    }

    private JFRConnection attemptConnectAsHostPortPair(ConnectionDescriptor connectionDescriptor)
            throws Exception {
        String s = connectionDescriptor.getTargetId();
        Matcher m = HOST_PORT_PAIR_PATTERN.matcher(s);
        if (!m.find()) {
            throw new MalformedURLException(s);
        }
        String host = m.group(1);
        String port = m.group(2);
        if (port == null) {
            port = "9091";
        }
        return connect(
                connectionDescriptor,
                jfrConnectionToolkit.get().createServiceURL(host, Integer.parseInt(port)),
                connectionDescriptor.getCredentials());
    }

    private JFRConnection connect(
            ConnectionDescriptor cacheKey, JMXServiceURL url, Optional<Credentials> credentials)
            throws Exception {
        logger.info("Creating connection for {}", url.toString());
        return jfrConnectionToolkit
                .get()
                .connect(
                        url,
                        credentials.orElse(null),
                        Collections.singletonList(() -> this.connections.invalidate(cacheKey)));
    }

    public interface ConnectedTask<T> {
        T execute(JFRConnection connection) throws Exception;
    }
}
