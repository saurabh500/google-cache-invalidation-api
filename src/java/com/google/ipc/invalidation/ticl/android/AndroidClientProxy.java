/*
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ipc.invalidation.ticl.android;

import com.google.common.base.Preconditions;
import com.google.ipc.invalidation.external.client.InvalidationClient;
import com.google.ipc.invalidation.external.client.InvalidationClientFactory;
import com.google.ipc.invalidation.external.client.InvalidationListener;
import com.google.ipc.invalidation.external.client.SystemResources;
import com.google.ipc.invalidation.external.client.android.AndroidInvalidationClient;
import com.google.ipc.invalidation.external.client.android.service.Event;
import com.google.ipc.invalidation.external.client.android.service.ListenerBinder;
import com.google.ipc.invalidation.external.client.android.service.ListenerService;
import com.google.ipc.invalidation.external.client.types.AckHandle;
import com.google.ipc.invalidation.external.client.types.ErrorInfo;
import com.google.ipc.invalidation.external.client.types.Invalidation;
import com.google.ipc.invalidation.external.client.types.ObjectId;
import com.google.protos.ipc.invalidation.AndroidState.ClientMetadata;

import android.accounts.Account;
import android.util.Log;

import java.util.Collection;

/**
 * A bidirectional client proxy that wraps and delegates requests to a TICL instance and routes
 * events generated by the TICL back to the associated listener.
 *
 */
class AndroidClientProxy implements AndroidInvalidationClient {

  /**
   * A reverse proxy for delegating raised invalidation events back to the client (via the
   * associated service).
   */
  class AndroidListenerProxy implements InvalidationListener {

    private static final String TAG = "AndroidListenerProxy";

    /** Binder that can be use to bind back to event listener service */
    
    final ListenerBinder binder;

    /**
     * Creates a new listener reverse proxy.
     */
    private AndroidListenerProxy() {
      this.binder = new ListenerBinder(Event.LISTENER_INTENT, metadata.getListenerClass());
    }

    @Override
    public void ready(InvalidationClient client) {
      Event event = Event.newBuilder(Event.Action.READY).setClientKey(clientKey).build();
      sendEvent(event);
    }

    @Override
    public void informRegistrationStatus(
        InvalidationClient client, ObjectId objectId, RegistrationState regState) {
      Event event = Event.newBuilder(Event.Action.INFORM_REGISTRATION_STATUS)
          .setClientKey(clientKey).setObjectId(objectId).setRegistrationState(regState).build();
      sendEvent(event);
    }

    @Override
    public void informRegistrationFailure(
        InvalidationClient client, ObjectId objectId, boolean isTransient, String errorMessage) {
      Event event = Event.newBuilder(Event.Action.INFORM_REGISTRATION_FAILURE)
          .setClientKey(clientKey).setObjectId(objectId).setIsTransient(isTransient)
          .setError(errorMessage).build();
      sendEvent(event);
    }

    @Override
    public void invalidate(
        InvalidationClient client, Invalidation invalidation, AckHandle ackHandle) {
      Event event = Event.newBuilder(Event.Action.INVALIDATE)
          .setClientKey(clientKey).setInvalidation(invalidation).setAckHandle(ackHandle).build();
      sendEvent(event);
    }

    @Override
    public void invalidateAll(InvalidationClient client, AckHandle ackHandle) {
      Event event = Event.newBuilder(Event.Action.INVALIDATE_ALL)
          .setClientKey(clientKey).setAckHandle(ackHandle).build();
      sendEvent(event);
    }

    @Override
    public void invalidateUnknownVersion(
        InvalidationClient client, ObjectId objectId, AckHandle ackHandle) {
      Event event = Event.newBuilder(Event.Action.INVALIDATE_UNKNOWN)
          .setClientKey(clientKey).setObjectId(objectId).setAckHandle(ackHandle).build();
      sendEvent(event);
    }

    @Override
    public void reissueRegistrations(InvalidationClient client, byte[] prefix, int prefixLength) {
      Event event = Event.newBuilder(Event.Action.REISSUE_REGISTRATIONS)
          .setClientKey(clientKey).setPrefix(prefix, prefixLength).build();
      sendEvent(event);
    }

    @Override
    public void informError(InvalidationClient client, ErrorInfo errorInfo) {
      Event event = Event.newBuilder(Event.Action.INFORM_ERROR)
          .setClientKey(clientKey).setErrorInfo(errorInfo).build();
      sendEvent(event);
    }

    /**
     * Releases any resources associated with the proxy listener.
     */
    public void release() {
      binder.unbind(service);
    }

    /**
     * Send event messages to application clients and provides common processing of the response.
     */
    private void sendEvent(Event event) {
      ListenerService listenerService = binder.bind(service);
      if (listenerService == null) {
        // If unable to bind to the client listener service, then log a warning
        // and exit. It's possible that the application has been uninstalled.
        Log.w(TAG, "Cannot bind using " + binder + " to send event:" + event);
        return;
      }
      Log.i(TAG, "Sending " + event.getAction() + " event to " + clientKey);
      service.sendEvent(listenerService, event);
    }
  }

  /** The service associated with this proxy */
  private final AndroidInvalidationService service;

  /** the client key for this client proxy */
  private final String clientKey;

  /** The invalidation client to delegate requests to */
  private final InvalidationClient delegate;

  /** The reverse listener proxy for this client proxy */
  private final AndroidListenerProxy listener;

  /** The stored state associated with this client */
  private final ClientMetadata metadata;

  /** The channel for this client */
  private final AndroidChannel channel;

  /** The system resources for this client */
  private final SystemResources resources;

  /** {@code true} if client is started */
  private boolean started;

  /**
   * Creates a new client proxy instance.
   *
   * @param service the service within which the client proxy is executing.
   * @param c2dmRegistrationId the c2dm registration ID for the application (or {@code null} if not
   *        yet acquired.
   * @param storage the storage instance that contains client metadata and can be used to read or
   *        write client properties.
   */
  AndroidClientProxy(AndroidInvalidationService service, String c2dmRegistrationId,
      AndroidStorage storage) {
    this.service = service;
    this.metadata = storage.getClientMetadata();
    this.clientKey = metadata.getClientKey();
    this.listener = new AndroidListenerProxy();

    this.channel =
        new AndroidChannel(this, AndroidChannel.getDefaultHttpClient(service), c2dmRegistrationId);
    this.resources =
        AndroidResourcesFactory.createResourcesBuilder(clientKey, channel, storage).build();
    this.delegate = createClient(resources, metadata.getClientType(), clientKey.getBytes(),
        metadata.getListenerPkg(), listener);
  }

  @Override
  public final Account getAccount() {
    return new Account(metadata.getAccountName(), metadata.getAccountType());
  }

  @Override
  public final String getAuthType() {
    return metadata.getAuthType();
  }

  @Override
  public final String getClientKey() {
    return metadata.getClientKey();
  }

  /** Returns the android service that is asociated with this proxy. */
  final AndroidInvalidationService getService() {
    return service;
  }

  /** Returns the network channel for this proxy. */
  final AndroidChannel getChannel() {
    return channel;
  }

  /** Returns the underlying invalidation client instance or {@code null} */
  
  final InvalidationClient getDelegate() {
    return delegate;
  }

  /** Returns the invalidation listener for this proxy */
  
  final AndroidListenerProxy getListener() {
    return listener;
  }

  boolean isStarted() {
    return started;
  }

  @Override
  public void start() {
    Preconditions.checkState(!started);
    resources.start();
    delegate.start();
    started = true;
  }

  @Override
  public void stop() {
    Preconditions.checkState(started);
    delegate.stop();
    started = false;

    // Remove any cached instance for this client. Any subsequent create or resume operations
    // will then have a clean, unstarted instance.
    service.getClientManager().remove(clientKey);
  }

  @Override
  public void register(Collection<ObjectId> objectIds) {
    delegate.register(objectIds);
  }

  @Override
  public void register(ObjectId objectId) {
    delegate.register(objectId);
  }

  @Override
  public void unregister(Collection<ObjectId> objectIds) {
    delegate.unregister(objectIds);
  }

  @Override
  public void unregister(ObjectId objectId) {
    delegate.unregister(objectId);
  }

  @Override
  public void acknowledge(AckHandle ackHandle) {
    delegate.acknowledge(ackHandle);
  }

  /**
   * Called when the client proxy is being removed from memory and will no longer be in use.
   * Releases any resources associated with the client proxy.
   */
  @Override
  public void release() {
    listener.release();
  }

  /**
   * Creates a new InvalidationClient instance that the proxy will delegate requests to and listen
   * for events from.
   */
  // Overridden by tests to inject mock clients or for listener interception
  
  InvalidationClient createClient(SystemResources resources, int clientType, byte[] clientName,
      String applicationName, InvalidationListener listener) {
    return InvalidationClientFactory.create(
        resources, clientType, clientName, applicationName, listener);
  }
}
