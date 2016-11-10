/*******************************************************************************
 * Copyright (c) 2013-2015 Sierra Wireless and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *     Sierra Wireless - initial API and implementation
 *******************************************************************************/
package org.eclipse.leshan.server.impl;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.leshan.server.Startable;
import org.eclipse.leshan.server.Stoppable;
import org.eclipse.leshan.server.client.Client;
import org.eclipse.leshan.server.client.ClientRegistry;
import org.eclipse.leshan.server.client.ClientRegistryListener;
import org.eclipse.leshan.server.client.ClientUpdate;
import org.eclipse.leshan.server.client.RegistrationStore;
import org.eclipse.leshan.util.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In memory client registry
 */
public class ClientRegistryImpl implements ClientRegistry, Startable, Stoppable {

    private static final Logger LOG = LoggerFactory.getLogger(ClientRegistryImpl.class);

    private final List<ClientRegistryListener> listeners = new CopyOnWriteArrayList<>();

    private RegistrationStore store;

    public ClientRegistryImpl(RegistrationStore store) {
        this.store = store;
    }

    @Override
    public void addListener(ClientRegistryListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(ClientRegistryListener listener) {
        listeners.remove(listener);
    }

    @Override
    public Collection<Client> allClients() {
        return Collections.unmodifiableCollection(store.getAllRegistration());
    }

    @Override
    public Client get(String endpoint) {
        return store.getRegistrationByEndpoint(endpoint);
    }

    @Override
    public boolean registerClient(Client client) {
        Validate.notNull(client);

        LOG.debug("Registering new client: {}", client);

        Client previous = store.addRegistration(client);
        if (previous != null) {
            for (ClientRegistryListener l : listeners) {
                l.unregistered(previous);
            }
        }
        for (ClientRegistryListener l : listeners) {
            l.registered(client);
        }

        return true;
    }

    @Override
    public Client updateClient(ClientUpdate update) {
        Validate.notNull(update);

        LOG.debug("Updating registration for client: {}", update);
        Client clientUpdated = store.updateRegistration(update);
        if (clientUpdated != null) {
            // notify listener
            for (ClientRegistryListener l : listeners) {
                l.updated(update, clientUpdated);
            }
            return clientUpdated;
        }
        return null;
    }

    @Override
    public Client deregisterClient(String registrationId) {
        Validate.notNull(registrationId);

        LOG.debug("Deregistering client with registrationId: {}", registrationId);

        Client unregistered = store.removeRegistration(registrationId);
        for (ClientRegistryListener l : listeners) {
            l.unregistered(unregistered);
        }
        LOG.debug("Deregistered client: {}", unregistered);
        return unregistered;
    }

    @Override
    public Client findByRegistrationId(String id) {
        return store.getRegistration(id);
    }

    /**
     * start the registration manager, will start regular cleanup of dead registrations.
     */
    @Override
    public void start() {
        // every 2 seconds clean the registration list
        // TODO re-consider clean-up interval: wouldn't 5 minutes do as well?
        schedExecutor.scheduleAtFixedRate(new Cleaner(), 2, 2, TimeUnit.SECONDS);
    }

    /**
     * Stop the underlying cleanup of the registrations.
     */
    @Override
    public void stop() {
        schedExecutor.shutdownNow();
        try {
            schedExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOG.warn("Clean up registration thread was interrupted.", e);
        }
    }

    private final ScheduledExecutorService schedExecutor = Executors.newScheduledThreadPool(1);

    private class Cleaner implements Runnable {

        @Override
        public void run() {
            try {
                for (Client client : store.getAllRegistration()) {
                    synchronized (client) {
                        if (!client.isAlive()) {
                            // force de-registration
                            deregisterClient(client.getRegistrationId());
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("Unexcepted Exception while registration cleaning", e);
            }
        }
    }
}
