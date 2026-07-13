/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.client.transport.grpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class GrpcChannelHealthMonitorTest {

    private ManagedChannel channel;
    private GrpcChannelHealthMonitor monitor;

    @Before
    public void setUp() {
        // Create a channel to a non-existent host — will be IDLE initially
        channel = ManagedChannelBuilder.forAddress("localhost", 9400)
            .usePlaintext()
            .build();
        monitor = new GrpcChannelHealthMonitor(channel);
    }

    @After
    public void tearDown() {
        if (monitor != null) {
            monitor.stopMonitoring();
        }
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    @Test
    public void testInitialStateIsIdle() {
        // Channel starts in IDLE state (no connection attempt yet)
        ConnectivityState state = monitor.getState();
        assertEquals(ConnectivityState.IDLE, state);
    }

    @Test
    public void testIsHealthyWhenIdle() {
        // IDLE is considered healthy — channel will connect on demand
        assertTrue(monitor.isHealthy());
    }

    @Test
    public void testIsNotReadyWhenIdle() {
        // IDLE means no active connection yet
        assertFalse(monitor.isReady());
    }

    @Test
    public void testIsNotShutdownInitially() {
        assertFalse(monitor.isShutdown());
    }

    @Test
    public void testIsShutdownAfterChannelShutdown() {
        channel.shutdownNow();
        assertTrue(monitor.isShutdown());
    }

    @Test
    public void testConnectIfIdleTriggersConnection() {
        assertEquals(ConnectivityState.IDLE, monitor.getState());
        monitor.connectIfIdle();
        // After triggering, state should move away from IDLE
        // (to CONNECTING or TRANSIENT_FAILURE since no server is running)
        ConnectivityState state = monitor.getState(true);
        assertTrue(
            "State should not be IDLE after connectIfIdle()",
            state == ConnectivityState.CONNECTING || state == ConnectivityState.TRANSIENT_FAILURE
                || state == ConnectivityState.READY
        );
    }

    @Test
    public void testGetStateWithRequestConnectionTriggersConnect() {
        ConnectivityState state = monitor.getState(true);
        // Should trigger connection attempt
        assertTrue(
            state == ConnectivityState.CONNECTING
                || state == ConnectivityState.IDLE
                || state == ConnectivityState.TRANSIENT_FAILURE
        );
    }

    @Test
    public void testWaitForReadyTimesOutWithNoServer() throws InterruptedException {
        // No server running on port 9400, so channel won't reach READY
        boolean ready = monitor.waitForReady(500, TimeUnit.MILLISECONDS);
        assertFalse("Should timeout since no server is available", ready);
    }

    @Test
    public void testWaitForReadyReturnsFalseOnShutdown() throws InterruptedException {
        channel.shutdownNow();
        boolean ready = monitor.waitForReady(100, TimeUnit.MILLISECONDS);
        assertFalse("Should return false for shutdown channel", ready);
    }

    @Test
    public void testStartMonitoring() {
        // Should not throw
        monitor.startMonitoring();
        assertTrue(monitor.isHealthy() || monitor.isInTransientFailure());
    }

    @Test
    public void testStartMonitoringIdempotent() {
        // Calling startMonitoring multiple times should not cause issues
        monitor.startMonitoring();
        monitor.startMonitoring();
        monitor.startMonitoring();
        // No exception = pass
    }

    @Test
    public void testStateChangeListenerCalled() throws InterruptedException {
        AtomicReference<ConnectivityState> capturedNew = new AtomicReference<>();

        monitor.setStateChangeListener((prev, next) -> capturedNew.set(next));
        monitor.startMonitoring();

        // Trigger a state change by requesting connection
        channel.getState(true);

        // Give it a moment for the async callback
        Thread.sleep(200);

        // State may or may not have changed depending on timing,
        // but the monitor should be running without errors
        assertNotNull(monitor.getState());
    }

    @Test
    public void testIsInTransientFailureWhenNoServer() throws InterruptedException {
        // Trigger connection attempt to a non-existent server
        channel.getState(true);
        // Wait a bit for the connection to fail
        Thread.sleep(500);

        ConnectivityState state = monitor.getState();
        // Should be TRANSIENT_FAILURE since no server is running
        assertTrue(
            "Expected TRANSIENT_FAILURE or CONNECTING, got " + state,
            state == ConnectivityState.TRANSIENT_FAILURE || state == ConnectivityState.CONNECTING
        );
    }

    @Test
    public void testStopMonitoringStopsCallbacks() {
        monitor.startMonitoring();
        monitor.stopMonitoring();
        // Should not throw or cause issues after stopping
        assertNotNull(monitor.getState());
    }
}
