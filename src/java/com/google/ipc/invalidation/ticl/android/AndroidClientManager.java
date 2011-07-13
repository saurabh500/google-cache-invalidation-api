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

import com.google.ipc.invalidation.external.client.android.service.AndroidClientException;
import com.google.ipc.invalidation.external.client.android.service.Response.Status;

import android.accounts.Account;
import android.content.Intent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages active client instances for the Android invalidation service. The client manager contains
 * the code to create, persist, load, and lookup client instances, as well as handling the
 * propogation of any C2DM registration notifications to active clients.
 *
 */
class AndroidClientManager {

  /** The invalidation service associated with this manager */
  private final AndroidInvalidationService service;

  /** A map from client key to client proxy instances for in-memory client instances */
  private final Map<String, AndroidClientProxy> clientMap =
      new ConcurrentHashMap<String, AndroidClientProxy>();

  /** The C2DM registration ID that should be used for all managed clients */
  private String registrationId;

  /** Creates a new client manager instance associated with the provided service */
  AndroidClientManager(AndroidInvalidationService service, String registrationId) {
    this.service = service;
    this.registrationId = registrationId;
  }

  /**
   * Returns the number of managed clients.
   */
  int getClientCount() {
    return clientMap.size();
  }

  /**
   * Creates a new Android client proxy with the provided attributes. Before creating, will check to
   * see if there is an existing client with attributes that match and return it if found. If there
   * is an existing client with the same key but attributes that do not match, an exception will be
   * thrown. If no client with a matching key exists, a new client proxy will be created and
   * returned.
   *
   * @param clientKey key that uniquely identifies the client on the device.
   * @param clientType client type.
   * @param account user account associated with the client {or @code null}.
   * @param eventIntent intent that can be used to bind to an event listener for the client.
   * @return an android invalidation client instance representing the client.
   * @throws AndroidClientException if an existing client is found that does not match, or a new
   *         client cannot be created.
   */
  AndroidClientProxy create(String clientKey, int clientType, Account account, Intent eventIntent)
      throws AndroidClientException {

    // First check to see if an existing client is found
    AndroidClientProxy proxy = lookup(clientKey);
    if (proxy != null) {
      if (!proxy.getAccount().equals(account)) {
        throw new AndroidClientException(
            Status.INVALID_CLIENT, "Account does not match existing client");
      }
      return proxy;
    }

    // If not found, create a new client proxy instance to represent the client.
    proxy = new AndroidClientProxy(service, registrationId, clientKey, clientType, account,
        eventIntent);

    clientMap.put(clientKey, proxy);
    // TODO: Persist client state in DB
    return proxy;
  }

  /**
   * Retrieves an existing client that matches the provided key, loading it if necessary. If no
   * matching client can be found, an exception is thrown.
   *
   * @param clientKey the client key for the client to retrieve.
   * @return the matching client instance.
   * @throws AndroidClientException if no matching client can be found.
   */
  AndroidClientProxy get(String clientKey) throws AndroidClientException {
    AndroidClientProxy client = lookup(clientKey);
    if (client != null) {
      return client;
    }
    // TODO: Look up and instantiate client from DB.
    throw new AndroidClientException(Status.INVALID_CLIENT, "Unknown client key:" + clientKey);
  }

  /**
   * Removes any client proxy instance associated with the provided key from memory but leaves the
   * instance persisted. The client may subsequently be loaded again by calling {@code #get}.
   *
   * @param clientKey the client key of the instance to remove from memory.
   */
  void remove(String clientKey) {
    clientMap.remove(clientKey);
  }

  /**
   * Deletes a client instance from memory and persisted storage.
   *
   * @param clientKey the client key of the instance to delete.
   */
  void delete(String clientKey) {
    remove(clientKey);
    // TODO: Remove from DB
  }

  /**
   * Looks up the client proxy instance associated with the provided key and returns it (or {@code
   * null} if not found).
   *
   * @param clientKey the client key to look up
   * @return the client instance or {@code null}.
   */
  
  AndroidClientProxy lookup(String clientKey) {
    AndroidClientProxy client = clientMap.get(clientKey);
    if (client != null) {
      return client;
    }
    return null;
  }

  /**
   * Sets the C2DM registration ID that should be used for all managed clients (new and existing).
   */
  void setRegistrationId(String registrationId) {

    // Set the value used for new clients
    this.registrationId = registrationId;

    // Propagate the value to all existing clients
    for (AndroidClientProxy proxy : clientMap.values()) {
      proxy.getChannel().setRegistrationId(registrationId);
    }
  }

  /**
   * Returns the current C2DM registration ID used for managed clients.
   */
   String getRegistrationId() {
    return registrationId;
  }

  /**
   * Releases all managed clients and drops them from the managed set.
   */
  void releaseAll() {
    for (AndroidClientProxy clientProxy : clientMap.values()) {
      clientProxy.release();
    }
    clientMap.clear();
  }
}