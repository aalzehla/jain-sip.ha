/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat, Inc. and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.ha.javax.sip.cache;

import java.io.IOException;
import java.net.InetAddress;
import java.text.ParseException;
import java.util.Map;

import javax.sip.PeerUnavailableException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.TransactionManager;

import org.mobicents.ha.javax.sip.ClusteredSipStack;
import org.restcomm.cache.CacheData;
import org.restcomm.cache.MobicentsCache;

import gov.nist.core.CommonLogger;
import gov.nist.core.StackLogger;
import gov.nist.javax.sip.stack.MessageChannel;
import gov.nist.javax.sip.stack.MessageProcessor;
import gov.nist.javax.sip.stack.MobicentsHASIPClientTransaction;
import gov.nist.javax.sip.stack.SIPClientTransaction;
import gov.nist.javax.sip.stack.SIPTransactionStack;

/**
 * @author jean.deruelle@gmail.com
 */
public class ClientTransactionCacheData extends CacheData<String,Map<String,Object>> {
	private static final String APPDATA = "APPDATA";
	private ClusteredSipStack clusteredSipStack;
	private MobicentsCache mobicentsCache;
	private StackLogger logger = CommonLogger.getLogger(ClientTransactionCacheData.class);
	
	public ClientTransactionCacheData(String txId, MobicentsCache mobicentsCache, ClusteredSipStack clusteredSipStack) {
		super(txId, mobicentsCache);
		this.clusteredSipStack = clusteredSipStack;
		this.mobicentsCache = mobicentsCache;
	}
	
	public SIPClientTransaction getClientTransaction() throws SipCacheException {
		SIPClientTransaction haSipClientTransaction = null;
		//final Cache jbossCache = getMobicentsCache().getJBossCache();
		//Configuration config = jbossCache.getConfiguration();
		final boolean isBuddyReplicationEnabled = mobicentsCache.isBuddyReplicationEnabled();
		TransactionManager transactionManager = mobicentsCache.getTxManager();
		boolean doTx = false;
		try {
			if(logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
				logger.logDebug("transaction manager :" + transactionManager);
			}
			if(transactionManager != null && transactionManager.getTransaction() == null) {
				if(logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
					logger.logDebug("transaction manager begin transaction");
				}
				transactionManager.begin();				
				doTx = true;				
	        }
			// Issue 1517 : http://code.google.com/p/restcomm/issues/detail?id=1517
			// Adding code to handle Buddy replication to force data gravitation   
			if(isBuddyReplicationEnabled) {     
				if(logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
					logger.logDebug("forcing data gravitation since buddy replication is enabled");
				}
				mobicentsCache.setForceDataGravitation(true);
			}
            //final Node<String,Object> childNode = getNode().getChild(txId);
			Map<String, Object> transactionMetaData = get();
			if(transactionMetaData != null) {
				try {
					//final Object dialogAppData = childNode.get(APPDATA);
					final Object dialogAppData = transactionMetaData.remove(APPDATA);
						
					haSipClientTransaction = createClientTransaction(transactionMetaData, dialogAppData);
				//} catch (CacheException e) {
				} catch (Exception e) {
					throw new SipCacheException("A problem occured while retrieving the following client transaction " + getKey() + " from the Cache", e);
				} 
			} else {
				if(logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
					logger.logDebug("no child node found for transactionId " + getKey());
				}				
			}
		} catch (Exception ex) {
			try {
				if(transactionManager != null) {
					// Let's set it no matter what.
					transactionManager.setRollbackOnly();
				}
			} catch (Exception exn) {
				logger.logError("Problem rolling back session mgmt transaction",
						exn);
			}			
		} finally {
			if (doTx) {
				try {
					if (transactionManager.getTransaction().getStatus() != Status.STATUS_MARKED_ROLLBACK) {
						if(logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
							logger.logDebug("transaction manager committing transaction");
						}
						transactionManager.commit();
					} else {
						if(logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
							logger.logDebug("endBatch(): rolling back batch");
						}
						transactionManager.rollback();
					}
				} catch (RollbackException re) {
					// Do nothing here since cache may rollback automatically.
					logger.logWarning("endBatch(): rolling back transaction with exception: "
									+ re);
				} catch (RuntimeException re) {
					throw re;
				} catch (Exception e) {
					throw new RuntimeException(
							"endTransaction(): Caught Exception ending batch: ",
							e);
				}
			}
		}
		return haSipClientTransaction;
	}
	
	public MobicentsHASIPClientTransaction createClientTransaction(Map<String, Object> transactionMetaData, Object transactionAppData) throws SipCacheException {
		MobicentsHASIPClientTransaction haClientTransaction = null; 
		if(transactionMetaData != null) {
		    String txId = getKey();
			if(logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
				logger.logDebug("sipStack " + this + " client transaction " + txId + " is present in the distributed cache, recreating it locally");
			}
			String channelTransport = (String) transactionMetaData.get(MobicentsHASIPClientTransaction.TRANSPORT);
			if (logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
				logger.logDebug(txId + " : transport " + channelTransport);
			}
			InetAddress channelIp = (InetAddress) transactionMetaData.get(MobicentsHASIPClientTransaction.PEER_IP);
			if (logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
				logger.logDebug(txId + " : channel peer Ip address " + channelIp);
			}
			Integer channelPort = (Integer) transactionMetaData.get(MobicentsHASIPClientTransaction.PEER_PORT);
			if (logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
				logger.logDebug(txId + " : channel peer port " + channelPort);
			}
			Integer myPort = (Integer) transactionMetaData.get(MobicentsHASIPClientTransaction.MY_PORT);
			if (logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
				logger.logDebug(txId + " : my port " + myPort);
			}
			MessageChannel messageChannel = null;
			MessageProcessor[] messageProcessors = clusteredSipStack.getStackMessageProcessors();
			for (MessageProcessor messageProcessor : messageProcessors) {
				if(messageProcessor.getTransport().equalsIgnoreCase(channelTransport)) {
					try {
						messageChannel = messageProcessor.createMessageChannel(channelIp, channelPort);
					} catch (IOException e) {
						logger.logError("couldn't recreate the message channel on ip address " 
								+ channelIp + " and port " + channelPort, e);
					}
					break;
				}
			}
			
			haClientTransaction = new MobicentsHASIPClientTransaction((SIPTransactionStack) clusteredSipStack, messageChannel);
			haClientTransaction.setBranch(txId);
			try {
				updateClientTransactionMetaData(transactionMetaData, transactionAppData, haClientTransaction, true);						
			} catch (PeerUnavailableException e) {
				throw new SipCacheException("A problem occured while retrieving the following transaction " + txId + " from the Cache", e);
			} catch (ParseException e) {
				throw new SipCacheException("A problem occured while retrieving the following transaction " + txId + " from the Cache", e);
			}
		} else {
			if(logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
				logger.logDebug("sipStack " + this + " client transaction " + getKey() + " not found in the distributed cache");
			}
		}
		
		return haClientTransaction;
	}

	/**
	 * Update the haSipDialog passed in param with the dialogMetaData and app meta data
	 * @param transactionMetaData
	 * @param transactionAppData
	 * @param haClientTransaction
	 * @throws ParseException
	 * @throws PeerUnavailableException
	 */
	private void updateClientTransactionMetaData(Map<String, Object> transactionMetaData, Object transactionAppData, MobicentsHASIPClientTransaction haClientTransaction, boolean recreation) throws ParseException,
			PeerUnavailableException {
		haClientTransaction.setMetaDataToReplicate(transactionMetaData, recreation);
		if(logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
			logger.logDebug("updating application data with the one from cache " + transactionAppData);
		}
		haClientTransaction.setApplicationDataToReplicate(transactionAppData);		
	}
	
	public void putClientTransaction(SIPClientTransaction clientTransaction) throws SipCacheException {
		if(logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
			logger.logStackTrace();
		}
		final MobicentsHASIPClientTransaction haClientTransaction = (MobicentsHASIPClientTransaction) clientTransaction;
		if(logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
			logger.logDebug("put HA SIP Client Transaction " + clientTransaction + " with id " + getKey());
		}
		//final Cache jbossCache = getMobicentsCache().getJBossCache();
		TransactionManager transactionManager = mobicentsCache.getTxManager();
		boolean doTx = false;
		try {
			if(logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
				logger.logDebug("transaction manager :" + transactionManager);
			}
			if(transactionManager != null && transactionManager.getTransaction() == null) {
				if(logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
					logger.logDebug("transaction manager begin transaction");
				}
				transactionManager.begin();				
				doTx = true;				
	        }					
			
			Map<String, Object> obj = haClientTransaction.getMetaDataToReplicate();
			final Object transactionAppData = haClientTransaction.getApplicationDataToReplicate();
            if(transactionAppData != null) {
                if(logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
                    logger.logDebug("replicating application data " + transactionAppData);
                }
                obj.put(APPDATA, transactionAppData);
            }
			put(obj);
		} catch (Exception ex) {
			try {
				if(transactionManager != null) {
					// Let's set it no matter what.
					transactionManager.setRollbackOnly();
				}
			} catch (Exception exn) {
				logger.logError("Problem rolling back session mgmt transaction",
						exn);
			}			
		} finally {
			if (doTx) {
				try {
					if (transactionManager.getTransaction().getStatus() != Status.STATUS_MARKED_ROLLBACK) {
						if(logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
							logger.logDebug("transaction manager committing transaction");
						}
						transactionManager.commit();
					} else {
						if(logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
							logger.logDebug("endBatch(): rolling back batch");
						}
						transactionManager.rollback();
					}
				} catch (RollbackException re) {
					// Do nothing here since cache may rollback automatically.
					logger.logWarning("endBatch(): rolling back transaction with exception: "
									+ re);
				} catch (RuntimeException re) {
					throw re;
				} catch (Exception e) {
					throw new RuntimeException(
							"endTransaction(): Caught Exception ending batch: ",
							e);
				}
			}
		}
	}

	public boolean removeClientTransaction() {
		if(logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
			logger.logDebug("remove HA SIP Client Transaction " + getKey());
		}
		boolean succeeded = false;
		//final Cache jbossCache = getMobicentsCache().getJBossCache();
		TransactionManager transactionManager = mobicentsCache.getTxManager();
		boolean doTx = false;
		try {
			if(logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
				logger.logDebug("transaction manager :" + transactionManager);
			}
			if(transactionManager != null && transactionManager.getTransaction() == null) {
				if(logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
					logger.logDebug("transaction manager begin transaction");
				}
				transactionManager.begin();				
				doTx = true;				
	        }			
			//succeeded = getNode().removeChild(transactionId);
			succeeded = remove() != null ;
			if(logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
				logger.logDebug("removed HA SIP Client Transaction ? " + succeeded);
			}
		} catch (Exception ex) {
			try {
				if(transactionManager != null) {
					// Let's set it no matter what.
					transactionManager.setRollbackOnly();
				}
			} catch (Exception exn) {
				logger.logError("Problem rolling back session mgmt transaction",
						exn);
			}			
		} finally {
			if (doTx) {
				try {
					if (transactionManager.getTransaction().getStatus() != Status.STATUS_MARKED_ROLLBACK) {
						if(logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
							logger.logDebug("transaction manager committing transaction");
						}
						transactionManager.commit();
					} else {
						if(logger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
							logger.logDebug("endBatch(): rolling back batch");
						}
						transactionManager.rollback();
					}
				} catch (RollbackException re) {
					// Do nothing here since cache may rollback automatically.
					logger.logWarning("endBatch(): rolling back transaction with exception: "
									+ re);
				} catch (RuntimeException re) {
					throw re;
				} catch (Exception e) {
					throw new RuntimeException(
							"endTransaction(): Caught Exception ending batch: ",
							e);
				}
			}
		}
		return succeeded;
	}
}
