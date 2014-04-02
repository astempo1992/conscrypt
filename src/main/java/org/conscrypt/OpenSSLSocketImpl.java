/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.conscrypt;

import dalvik.system.BlockGuard;
import dalvik.system.CloseGuard;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.net.ssl.HandshakeCompletedEvent;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLProtocolException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;
import libcore.io.ErrnoException;
import libcore.io.Libcore;
import libcore.io.Streams;
import libcore.io.StructTimeval;

import static libcore.io.OsConstants.SOL_SOCKET;
import static libcore.io.OsConstants.SO_SNDTIMEO;

/**
 * Implementation of the class OpenSSLSocketImpl based on OpenSSL.
 * <p>
 * Extensions to SSLSocket include:
 * <ul>
 * <li>handshake timeout
 * <li>session tickets
 * <li>Server Name Indication
 * </ul>
 */
public class OpenSSLSocketImpl
        extends javax.net.ssl.SSLSocket
        implements NativeCrypto.SSLHandshakeCallbacks {

    private static final boolean DBG_STATE = false;

    /**
     * Protects handshakeStarted and handshakeCompleted.
     */
    private final Object stateLock = new Object();

    /**
     * The {@link OpenSSLSocketImpl} object is constructed, but {@link #startHandshake()}
     * has not yet been called.
     */
    private static final int STATE_NEW = 0;

    /**
     * {@link #startHandshake()} has been called at least once.
     */
    private static final int STATE_HANDSHAKE_STARTED = 1;

    /**
     * {@link #handshakeCompleted()} has been called, but {@link #startHandshake()} hasn't
     * returned yet.
     */
    private static final int STATE_HANDSHAKE_COMPLETED = 2;

    /**
     * {@link #startHandshake()} has completed but {@link #handshakeCompleted()} hasn't
     * been called. This is expected behaviour in cut-through mode, where SSL_do_handshake
     * returns before the handshake is complete. We can now start writing data to the socket.
     */
    private static final int STATE_READY_HANDSHAKE_CUT_THROUGH = 3;

    /**
     * {@link #startHandshake()} has completed and {@link #handshakeCompleted()} has been
     * called.
     */
    private static final int STATE_READY = 4;

    /**
     * {@link #close()} has been called at least once.
     */
    private static final int STATE_CLOSED = 5;

    // @GuardedBy("stateLock");
    private int state = STATE_NEW;

    /**
     * Protected by synchronizing on stateLock. Starts as 0, set by
     * startHandshake, reset to 0 on close.
     */
    // @GuardedBy("stateLock");
    private long sslNativePointer;

    /**
     * Protected by synchronizing on stateLock. Starts as null, set by
     * getInputStream.
     */
    // @GuardedBy("stateLock");
    private SSLInputStream is;

    /**
     * Protected by synchronizing on stateLock. Starts as null, set by
     * getInputStream.
     */
    // @GuardedBy("stateLock");
    private SSLOutputStream os;

    private final Socket socket;
    private final boolean autoClose;
    private String wrappedHost;
    private final int wrappedPort;
    private final SSLParametersImpl sslParameters;
    private final CloseGuard guard = CloseGuard.get();

    private String[] enabledProtocols;
    private String[] enabledCipherSuites;
    private byte[] npnProtocols;
    private byte[] alpnProtocols;
    private boolean useSessionTickets;
    private boolean useSni;

    /**
     * Whether the TLS Channel ID extension is enabled. This field is
     * server-side only.
     */
    private boolean channelIdEnabled;

    /**
     * Private key for the TLS Channel ID extension. This field is
     * client-side only. Set during startHandshake.
     */
    private OpenSSLKey channelIdPrivateKey;

    /** Set during startHandshake. */
    private OpenSSLSessionImpl sslSession;

    /** Used during handshake callbacks. */
    private OpenSSLSessionImpl handshakeSession;

    private ArrayList<HandshakeCompletedListener> listeners;

    /**
     * Local cache of timeout to avoid getsockopt on every read and
     * write for non-wrapped sockets. Note that
     * OpenSSLSocketImplWrapper overrides setSoTimeout and
     * getSoTimeout to delegate to the wrapped socket.
     */
    private int readTimeoutMilliseconds = 0;
    private int writeTimeoutMilliseconds = 0;

    private int handshakeTimeoutMilliseconds = -1;  // -1 = same as timeout; 0 = infinite

    protected OpenSSLSocketImpl(SSLParametersImpl sslParameters) throws IOException {
        this.socket = this;
        this.wrappedHost = null;
        this.wrappedPort = -1;
        this.autoClose = false;
        this.sslParameters = sslParameters;
        this.enabledProtocols = NativeCrypto.getDefaultProtocols();
        this.enabledCipherSuites = NativeCrypto.getDefaultCipherSuites();
    }

    protected OpenSSLSocketImpl(SSLParametersImpl sslParameters,
                                String[] enabledProtocols,
                                String[] enabledCipherSuites) throws IOException {
        this.socket = this;
        this.wrappedHost = null;
        this.wrappedPort = -1;
        this.autoClose = false;
        this.sslParameters = sslParameters;
        this.enabledProtocols = enabledProtocols;
        this.enabledCipherSuites = enabledCipherSuites;
    }

    protected OpenSSLSocketImpl(String host, int port, SSLParametersImpl sslParameters)
            throws IOException {
        super(host, port);
        this.socket = this;
        this.wrappedHost = null;
        this.wrappedPort = -1;
        this.autoClose = false;
        this.sslParameters = sslParameters;
        this.enabledProtocols = NativeCrypto.getDefaultProtocols();
        this.enabledCipherSuites = NativeCrypto.getDefaultCipherSuites();
    }

    protected OpenSSLSocketImpl(InetAddress address, int port, SSLParametersImpl sslParameters)
            throws IOException {
        super(address, port);
        this.socket = this;
        this.wrappedHost = null;
        this.wrappedPort = -1;
        this.autoClose = false;
        this.sslParameters = sslParameters;
        this.enabledProtocols = NativeCrypto.getDefaultProtocols();
        this.enabledCipherSuites = NativeCrypto.getDefaultCipherSuites();
    }


    protected OpenSSLSocketImpl(String host, int port,
                                InetAddress clientAddress, int clientPort,
                                SSLParametersImpl sslParameters) throws IOException {
        super(host, port, clientAddress, clientPort);
        this.socket = this;
        this.wrappedHost = null;
        this.wrappedPort = -1;
        this.autoClose = false;
        this.sslParameters = sslParameters;
        this.enabledProtocols = NativeCrypto.getDefaultProtocols();
        this.enabledCipherSuites = NativeCrypto.getDefaultCipherSuites();
    }

    protected OpenSSLSocketImpl(InetAddress address, int port,
                                InetAddress clientAddress, int clientPort,
                                SSLParametersImpl sslParameters) throws IOException {
        super(address, port, clientAddress, clientPort);
        this.socket = this;
        this.wrappedHost = null;
        this.wrappedPort = -1;
        this.autoClose = false;
        this.sslParameters = sslParameters;
        this.enabledProtocols = NativeCrypto.getDefaultProtocols();
        this.enabledCipherSuites = NativeCrypto.getDefaultCipherSuites();
    }

    /**
     * Create an SSL socket that wraps another socket. Invoked by
     * OpenSSLSocketImplWrapper constructor.
     */
    protected OpenSSLSocketImpl(Socket socket, String host, int port,
            boolean autoClose, SSLParametersImpl sslParameters) throws IOException {
        this.socket = socket;
        this.wrappedHost = host;
        this.wrappedPort = port;
        this.autoClose = autoClose;
        this.sslParameters = sslParameters;
        this.enabledProtocols = NativeCrypto.getDefaultProtocols();
        this.enabledCipherSuites = NativeCrypto.getDefaultCipherSuites();

        // this.timeout is not set intentionally.
        // OpenSSLSocketImplWrapper.getSoTimeout will delegate timeout
        // to wrapped socket
    }

    /**
     * Gets the suitable session reference from the session cache container.
     */
    private OpenSSLSessionImpl getCachedClientSession(ClientSessionContext sessionContext) {
        String hostname = getPeerHostName();
        if (hostname == null) {
            return null;
        }
        int port = getPeerPort();
        OpenSSLSessionImpl session = (OpenSSLSessionImpl) sessionContext.getSession(hostname, port);
        if (session == null) {
            return null;
        }

        String protocol = session.getProtocol();
        boolean protocolFound = false;
        for (String enabledProtocol : enabledProtocols) {
            if (protocol.equals(enabledProtocol)) {
                protocolFound = true;
                break;
            }
        }
        if (!protocolFound) {
            return null;
        }

        String cipherSuite = session.getCipherSuite();
        boolean cipherSuiteFound = false;
        for (String enabledCipherSuite : enabledCipherSuites) {
            if (cipherSuite.equals(enabledCipherSuite)) {
                cipherSuiteFound = true;
                break;
            }
        }
        if (!cipherSuiteFound) {
            return null;
        }

        return session;
    }

    private void checkOpen() throws SocketException {
        if (isClosed()) {
            throw new SocketException("Socket is closed");
        }
    }

    /**
     * Starts a TLS/SSL handshake on this connection using some native methods
     * from the OpenSSL library. It can negotiate new encryption keys, change
     * cipher suites, or initiate a new session. The certificate chain is
     * verified if the correspondent property in java.Security is set. All
     * listeners are notified at the end of the TLS/SSL handshake.
     */
    @Override
    public void startHandshake() throws IOException {
        checkOpen();
        synchronized (stateLock) {
            if (state == STATE_NEW) {
                state = STATE_HANDSHAKE_STARTED;
            } else {
                // We've either started the handshake already or have been closed.
                // Do nothing in both cases.
                return;
            }
        }

        // note that this modifies the global seed, not something specific to the connection
        final int seedLengthInBytes = NativeCrypto.RAND_SEED_LENGTH_IN_BYTES;
        final SecureRandom secureRandom = sslParameters.getSecureRandomMember();
        if (secureRandom == null) {
            NativeCrypto.RAND_load_file("/dev/urandom", seedLengthInBytes);
        } else {
            NativeCrypto.RAND_seed(secureRandom.generateSeed(seedLengthInBytes));
        }

        final boolean client = sslParameters.getUseClientMode();

        final long sslCtxNativePointer = (client) ?
                sslParameters.getClientSessionContext().sslCtxNativePointer :
                sslParameters.getServerSessionContext().sslCtxNativePointer;

        sslNativePointer = 0;
        boolean releaseResources = true;
        try {
            sslNativePointer = NativeCrypto.SSL_new(sslCtxNativePointer);
            guard.open("close");

            if (npnProtocols != null) {
                NativeCrypto.SSL_CTX_enable_npn(sslCtxNativePointer);
            }

            if (client && alpnProtocols != null) {
                NativeCrypto.SSL_set_alpn_protos(sslNativePointer, alpnProtocols);
            }

            NativeCrypto.setEnabledProtocols(sslNativePointer, enabledProtocols);
            NativeCrypto.setEnabledCipherSuites(sslNativePointer, enabledCipherSuites);
            if (useSessionTickets) {
                NativeCrypto.SSL_clear_options(sslNativePointer, NativeCrypto.SSL_OP_NO_TICKET);
            }
            if (useSni) {
                NativeCrypto.SSL_set_tlsext_host_name(sslNativePointer, getPeerHostName());
            }

            // BEAST attack mitigation (1/n-1 record splitting for CBC cipher suites with TLSv1 and
            // SSLv3).
            NativeCrypto.SSL_set_mode(
                    sslNativePointer, NativeCrypto.SSL_MODE_CBC_RECORD_SPLITTING);

            boolean enableSessionCreation = sslParameters.getEnableSessionCreation();
            if (!enableSessionCreation) {
                NativeCrypto.SSL_set_session_creation_enabled(sslNativePointer,
                                                              enableSessionCreation);
            }

            AbstractSessionContext sessionContext;
            OpenSSLSessionImpl sessionToReuse;
            if (client) {
                // look for client session to reuse
                ClientSessionContext clientSessionContext = sslParameters.getClientSessionContext();
                sessionContext = clientSessionContext;
                sessionToReuse = getCachedClientSession(clientSessionContext);
                if (sessionToReuse != null) {
                    NativeCrypto.SSL_set_session(sslNativePointer,
                                                 sessionToReuse.sslSessionNativePointer);
                }
            } else {
                sessionContext = sslParameters.getServerSessionContext();
                sessionToReuse = null;
            }

            // setup server certificates and private keys.
            // clients will receive a call back to request certificates.
            if (!client) {
                Set<String> keyTypes = new HashSet<String>();
                for (long sslCipherNativePointer : NativeCrypto.SSL_get_ciphers(sslNativePointer)) {
                    String keyType = getServerKeyType(sslCipherNativePointer);
                    if (keyType != null) {
                        keyTypes.add(keyType);
                    }
                }
                X509KeyManager x509KeyManager = sslParameters.getX509KeyManager();
                if (x509KeyManager != null) {
                    for (String keyType : keyTypes) {
                        try {
                            setCertificate(
                                    x509KeyManager.chooseServerAlias(keyType, null, this));
                        } catch (CertificateEncodingException e) {
                            throw new IOException(e);
                        }
                    }
                }
            }

            // setup peer certificate verification
            if (!client) {
                // needing client auth takes priority...
                boolean certRequested;
                if (sslParameters.getNeedClientAuth()) {
                    NativeCrypto.SSL_set_verify(sslNativePointer,
                                                NativeCrypto.SSL_VERIFY_PEER
                                                | NativeCrypto.SSL_VERIFY_FAIL_IF_NO_PEER_CERT);
                    certRequested = true;
                // ... over just wanting it...
                } else if (sslParameters.getWantClientAuth()) {
                    NativeCrypto.SSL_set_verify(sslNativePointer,
                                                NativeCrypto.SSL_VERIFY_PEER);
                    certRequested = true;
                // ... and it defaults properly so don't call SSL_set_verify in the common case.
                } else {
                    certRequested = false;
                }

                if (certRequested) {
                    X509TrustManager x509TrustManager = sslParameters.getX509TrustManager();
                    if (x509TrustManager != null) {
                        X509Certificate[] issuers = x509TrustManager.getAcceptedIssuers();
                        if (issuers != null && issuers.length != 0) {
                            byte[][] issuersBytes;
                            try {
                                issuersBytes = encodeIssuerX509Principals(issuers);
                            } catch (CertificateEncodingException e) {
                                throw new IOException("Problem encoding principals", e);
                            }
                            NativeCrypto.SSL_set_client_CA_list(sslNativePointer, issuersBytes);
                        }
                    }
                }
            }

            // Temporarily use a different timeout for the handshake process
            int savedReadTimeoutMilliseconds = getSoTimeout();
            int savedWriteTimeoutMilliseconds = getSoWriteTimeout();
            if (handshakeTimeoutMilliseconds >= 0) {
                setSoTimeout(handshakeTimeoutMilliseconds);
                setSoWriteTimeout(handshakeTimeoutMilliseconds);
            }

            // TLS Channel ID
            if (channelIdEnabled) {
                if (client) {
                    // Client-side TLS Channel ID
                    if (channelIdPrivateKey == null) {
                        throw new SSLHandshakeException("Invalid TLS channel ID key specified");
                    }
                    NativeCrypto.SSL_set1_tls_channel_id(sslNativePointer,
                            channelIdPrivateKey.getPkeyContext());
                } else {
                    // Server-side TLS Channel ID
                    NativeCrypto.SSL_enable_tls_channel_id(sslNativePointer);
                }
            }

            synchronized (stateLock) {
                if (state == STATE_CLOSED) {
                    return;
                }
            }

            long sslSessionNativePointer;
            try {
                sslSessionNativePointer = NativeCrypto.SSL_do_handshake(sslNativePointer,
                        socket.getFileDescriptor$(), this, getSoTimeout(), client, npnProtocols,
                        client ? null : alpnProtocols);
            } catch (CertificateException e) {
                SSLHandshakeException wrapper = new SSLHandshakeException(e.getMessage());
                wrapper.initCause(e);
                throw wrapper;
            } catch (SSLException e) {
                // Swallow this exception if it's thrown as the result of an interruption.
                //
                // TODO: SSL_read and SSL_write return -1 when interrupted, but SSL_do_handshake
                // will throw the last sslError that it saw before sslSelect, usually SSL_WANT_READ
                // (or WANT_WRITE). Catching that exception here doesn't seem much worse than
                // changing the native code to return a "special" native pointer value when that
                // happens.
                synchronized (stateLock) {
                    if (state == STATE_CLOSED) {
                        return;
                    }
                }

                throw e;
            }

            boolean handshakeCompleted = false;
            synchronized (stateLock) {
                if (state == STATE_HANDSHAKE_COMPLETED) {
                    handshakeCompleted = true;
                } else if (state == STATE_CLOSED) {
                    return;
                }
            }

            byte[] sessionId = NativeCrypto.SSL_SESSION_session_id(sslSessionNativePointer);
            if (sessionToReuse != null && Arrays.equals(sessionToReuse.getId(), sessionId)) {
                this.sslSession = sessionToReuse;
                sslSession.lastAccessedTime = System.currentTimeMillis();
                NativeCrypto.SSL_SESSION_free(sslSessionNativePointer);
            } else {
                if (!enableSessionCreation) {
                    // Should have been prevented by NativeCrypto.SSL_set_session_creation_enabled
                    throw new IllegalStateException("SSL Session may not be created");
                }
                X509Certificate[] localCertificates
                        = createCertChain(NativeCrypto.SSL_get_certificate(sslNativePointer));
                X509Certificate[] peerCertificates
                        = createCertChain(NativeCrypto.SSL_get_peer_cert_chain(sslNativePointer));
                this.sslSession = new OpenSSLSessionImpl(sslSessionNativePointer, localCertificates,
                        peerCertificates, getPeerHostName(), getPeerPort(), sessionContext);
                // if not, putSession later in handshakeCompleted() callback
                if (handshakeCompleted) {
                    sessionContext.putSession(sslSession);
                }
            }

            // Restore the original timeout now that the handshake is complete
            if (handshakeTimeoutMilliseconds >= 0) {
                setSoTimeout(savedReadTimeoutMilliseconds);
                setSoWriteTimeout(savedWriteTimeoutMilliseconds);
            }

            // if not, notifyHandshakeCompletedListeners later in handshakeCompleted() callback
            if (handshakeCompleted) {
                notifyHandshakeCompletedListeners();
            }

            synchronized (stateLock) {
                releaseResources = (state == STATE_CLOSED);

                if (state == STATE_HANDSHAKE_STARTED) {
                    state = STATE_READY_HANDSHAKE_CUT_THROUGH;
                } else if (state == STATE_HANDSHAKE_COMPLETED) {
                    state = STATE_READY;
                }

                if (!releaseResources) {
                    // Unblock threads that are waiting for our state to transition
                    // into STATE_READY or STATE_READY_HANDSHAKE_CUT_THROUGH.
                    stateLock.notifyAll();
                }
            }
        } catch (SSLProtocolException e) {
            throw new SSLHandshakeException(e);
        } finally {
            // on exceptional exit, treat the socket as closed
            if (releaseResources) {
                synchronized (stateLock) {
                    // Mark the socket as closed since we might have reached this as
                    // a result on an exception thrown by the handshake process.
                    //
                    // The state will already be set to closed if we reach this as a result of
                    // an early return or an interruption due to a concurrent call to close().
                    state = STATE_CLOSED;
                    stateLock.notifyAll();
                }

                try {
                    shutdownAndFreeSslNative();
                } catch (IOException ignored) {

                }
            }
        }
    }

    private static byte[][] encodeIssuerX509Principals(X509Certificate[] certificates)
            throws CertificateEncodingException {
        byte[][] principalBytes = new byte[certificates.length][];
        for (int i = 0; i < certificates.length; i++) {
            principalBytes[i] = certificates[i].getIssuerX500Principal().getEncoded();
        }
        return principalBytes;
    }

    String getPeerHostName() {
        if (wrappedHost != null) {
            return wrappedHost;
        }
        InetAddress inetAddress = super.getInetAddress();
        if (inetAddress != null) {
            return inetAddress.getHostName();
        }
        return null;
    }

    int getPeerPort() {
        return wrappedHost == null ? super.getPort() : wrappedPort;
    }

    /**
     * Return a possibly null array of X509Certificates given the
     * possibly null array of DER encoded bytes.
     */
    private static OpenSSLX509Certificate[] createCertChain(long[] certificateRefs)
            throws IOException {
        if (certificateRefs == null) {
            return null;
        }
        OpenSSLX509Certificate[] certificates = new OpenSSLX509Certificate[certificateRefs.length];
        for (int i = 0; i < certificateRefs.length; i++) {
            certificates[i] = new OpenSSLX509Certificate(certificateRefs[i]);
        }
        return certificates;
    }

    private void setCertificate(String alias) throws CertificateEncodingException, SSLException {
        if (alias == null) {
            return;
        }
        X509KeyManager keyManager = sslParameters.getX509KeyManager();
        if (keyManager == null) {
            return;
        }
        PrivateKey privateKey = keyManager.getPrivateKey(alias);
        if (privateKey == null) {
            return;
        }
        X509Certificate[] certificates =
                keyManager.getCertificateChain(alias);
        if (certificates == null) {
            return;
        }

        /*
         * Make sure we keep a reference to the OpenSSLX509Certificate by using
         * this array. Otherwise, if they're not OpenSSLX509Certificate
         * instances originally, they may be garbage collected before we complete
         * our JNI calls.
         */
        OpenSSLX509Certificate[] openSslCerts = new OpenSSLX509Certificate[certificates.length];
        long[] x509refs = new long[certificates.length];
        for (int i = 0; i < certificates.length; i++) {
            OpenSSLX509Certificate openSslCert = OpenSSLX509Certificate
                    .fromCertificate(certificates[i]);
            openSslCerts[i] = openSslCert;
            x509refs[i] = openSslCert.getContext();
        }

        // Note that OpenSSL says to use SSL_use_certificate before SSL_use_PrivateKey.
        NativeCrypto.SSL_use_certificate(sslNativePointer, x509refs);

        try {
            final OpenSSLKey key = OpenSSLKey.fromPrivateKey(privateKey);
            NativeCrypto.SSL_use_PrivateKey(sslNativePointer, key.getPkeyContext());
        } catch (InvalidKeyException e) {
            throw new SSLException(e);
        }

        // checks the last installed private key and certificate,
        // so need to do this once per loop iteration
        NativeCrypto.SSL_check_private_key(sslNativePointer);
    }

    @Override
    @SuppressWarnings("unused") // used by NativeCrypto.SSLHandshakeCallbacks / client_cert_cb
    public void clientCertificateRequested(byte[] keyTypeBytes, byte[][] asn1DerEncodedPrincipals)
            throws CertificateEncodingException, SSLException {

        String[] keyTypes = new String[keyTypeBytes.length];
        for (int i = 0; i < keyTypeBytes.length; i++) {
            keyTypes[i] = getClientKeyType(keyTypeBytes[i]);
        }

        X500Principal[] issuers;
        if (asn1DerEncodedPrincipals == null) {
            issuers = null;
        } else {
            issuers = new X500Principal[asn1DerEncodedPrincipals.length];
            for (int i = 0; i < asn1DerEncodedPrincipals.length; i++) {
                issuers[i] = new X500Principal(asn1DerEncodedPrincipals[i]);
            }
        }
        X509KeyManager keyManager = sslParameters.getX509KeyManager();
        String alias =
                (keyManager != null) ? keyManager.chooseClientAlias(keyTypes, issuers, this) : null;
        setCertificate(alias);
    }

    @Override
    @SuppressWarnings("unused") // used by NativeCrypto.SSLHandshakeCallbacks / info_callback
    public void handshakeCompleted() {
        synchronized (stateLock) {
            if (state == STATE_HANDSHAKE_STARTED) {
                // If sslSession is null, the handshake was completed during
                // the call to NativeCrypto.SSL_do_handshake and not during a
                // later read operation. That means we do not need to fix up
                // the SSLSession and session cache or notify
                // HandshakeCompletedListeners, it will be done in
                // startHandshake.

                state = STATE_HANDSHAKE_COMPLETED;
                return;
            } else if (state == STATE_READY_HANDSHAKE_CUT_THROUGH) {
                // We've returned from startHandshake, which means we've set a sslSession etc.
                // we need to fix them up, which we'll do outside this lock.
            } else if (state == STATE_CLOSED) {
                // Someone called "close" but the handshake hasn't been interrupted yet.
                return;
            }
        }

        // reset session id from the native pointer and update the
        // appropriate cache.
        sslSession.resetId();
        AbstractSessionContext sessionContext =
            (sslParameters.getUseClientMode())
            ? sslParameters.getClientSessionContext()
                : sslParameters.getServerSessionContext();
        sessionContext.putSession(sslSession);

        // let listeners know we are finally done
        notifyHandshakeCompletedListeners();

        synchronized (stateLock) {
            // Now that we've fixed up our state, we can tell waiting threads that
            // we're ready.
            state = STATE_READY;
            // Notify all threads waiting for the handshake to complete.
            stateLock.notifyAll();
        }
    }

    private void notifyHandshakeCompletedListeners() {
        if (listeners != null && !listeners.isEmpty()) {
            // notify the listeners
            HandshakeCompletedEvent event =
                new HandshakeCompletedEvent(this, sslSession);
            for (HandshakeCompletedListener listener : listeners) {
                try {
                    listener.handshakeCompleted(event);
                } catch (RuntimeException e) {
                    // The RI runs the handlers in a separate thread,
                    // which we do not. But we try to preserve their
                    // behavior of logging a problem and not killing
                    // the handshaking thread just because a listener
                    // has a problem.
                    Thread thread = Thread.currentThread();
                    thread.getUncaughtExceptionHandler().uncaughtException(thread, e);
                }
            }
        }
    }

    @SuppressWarnings("unused") // used by NativeCrypto.SSLHandshakeCallbacks
    @Override
    public void verifyCertificateChain(long sslSessionNativePtr, long[] certRefs, String authMethod)
            throws CertificateException {
        try {
            X509TrustManager x509tm = sslParameters.getX509TrustManager();
            if (x509tm == null) {
                throw new CertificateException("No X.509 TrustManager");
            }
            if (certRefs == null || certRefs.length == 0) {
                throw new SSLException("Peer sent no certificate");
            }
            OpenSSLX509Certificate[] peerCertChain = new OpenSSLX509Certificate[certRefs.length];
            for (int i = 0; i < certRefs.length; i++) {
                peerCertChain[i] = new OpenSSLX509Certificate(certRefs[i]);
            }

            // Used for verifyCertificateChain callback
            handshakeSession = new OpenSSLSessionImpl(sslSessionNativePtr, null, peerCertChain,
                    getPeerHostName(), getPeerPort(), null);

            boolean client = sslParameters.getUseClientMode();
            if (client) {
                if (x509tm instanceof X509ExtendedTrustManager) {
                    X509ExtendedTrustManager x509etm = (X509ExtendedTrustManager) x509tm;
                    x509etm.checkServerTrusted(peerCertChain, authMethod, this);
                } else {
                    x509tm.checkServerTrusted(peerCertChain, authMethod);
                }
            } else {
                String authType = peerCertChain[0].getPublicKey().getAlgorithm();
                if (x509tm instanceof X509ExtendedTrustManager) {
                    X509ExtendedTrustManager x509etm = (X509ExtendedTrustManager) x509tm;
                    x509etm.checkClientTrusted(peerCertChain, authType, this);
                } else {
                    x509tm.checkClientTrusted(peerCertChain, authType);
                }
            }
        } catch (CertificateException e) {
            throw e;
        } catch (Exception e) {
            throw new CertificateException(e);
        } finally {
            // Clear this before notifying handshake completed listeners
            handshakeSession = null;
        }
    }

    @Override
    public InputStream getInputStream() throws IOException {
        checkOpen();

        InputStream returnVal;
        synchronized (stateLock) {
            if (state == STATE_CLOSED) {
                throw new SocketException("Socket is closed.");
            }

            if (is == null) {
                is = new SSLInputStream();
            }

            returnVal = is;
        }

        // Block waiting for a handshake without a lock held. It's possible that the socket
        // is closed at this point. If that happens, we'll still return the input stream but
        // all reads on it will throw.
        waitForHandshake();
        return returnVal;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        checkOpen();

        OutputStream returnVal;
        synchronized (stateLock) {
            if (state == STATE_CLOSED) {
                throw new SocketException("Socket is closed.");
            }

            if (os == null) {
                os = new SSLOutputStream();
            }

            returnVal = os;
        }

        // Block waiting for a handshake without a lock held. It's possible that the socket
        // is closed at this point. If that happens, we'll still return the output stream but
        // all writes on it will throw.
        waitForHandshake();
        return returnVal;
    }

    private void assertReadableOrWriteableState() {
        if (state == STATE_READY || state == STATE_READY_HANDSHAKE_CUT_THROUGH) {
            return;
        }

        throw new AssertionError("Invalid state: " + state);
    }


    private void waitForHandshake() throws IOException {
        startHandshake();

        synchronized (stateLock) {
            while (state != STATE_READY &&
                    state != STATE_READY_HANDSHAKE_CUT_THROUGH &&
                    state != STATE_CLOSED) {
                try {
                    stateLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    IOException ioe = new IOException("Interrupted waiting for handshake");
                    ioe.initCause(e);

                    throw ioe;
                }
            }

            if (state == STATE_CLOSED) {
                throw new SocketException("Socket is closed");
            }
        }
    }

    /**
     * This inner class provides input data stream functionality
     * for the OpenSSL native implementation. It is used to
     * read data received via SSL protocol.
     */
    private class SSLInputStream extends InputStream {
        /**
         * OpenSSL only lets one thread read at a time, so this is used to
         * make sure we serialize callers of SSL_read. Thread is already
         * expected to have completed handshaking.
         */
        private final Object readLock = new Object();

        SSLInputStream() {
        }

        /**
         * Reads one byte. If there is no data in the underlying buffer,
         * this operation can block until the data will be
         * available.
         * @return read value.
         * @throws IOException
         */
        @Override
        public int read() throws IOException {
            return Streams.readSingleByte(this);
        }

        /**
         * Method acts as described in spec for superclass.
         * @see java.io.InputStream#read(byte[],int,int)
         */
        @Override
        public int read(byte[] buf, int offset, int byteCount) throws IOException {
            BlockGuard.getThreadPolicy().onNetwork();

            checkOpen();
            Arrays.checkOffsetAndCount(buf.length, offset, byteCount);
            if (byteCount == 0) {
                return 0;
            }

            synchronized (readLock) {
                synchronized (stateLock) {
                    if (state == STATE_CLOSED) {
                        throw new SocketException("socket is closed");
                    }

                    if (DBG_STATE) assertReadableOrWriteableState();
                }

                return NativeCrypto.SSL_read(sslNativePointer, socket.getFileDescriptor$(),
                        OpenSSLSocketImpl.this, buf, offset, byteCount, getSoTimeout());
            }
        }

        public void awaitPendingOps() {
            if (DBG_STATE) {
                synchronized (stateLock) {
                    if (state != STATE_CLOSED) throw new AssertionError("State is: " + state);
                }
            }

            synchronized (readLock) { }
        }
    }

    /**
     * This inner class provides output data stream functionality
     * for the OpenSSL native implementation. It is used to
     * write data according to the encryption parameters given in SSL context.
     */
    private class SSLOutputStream extends OutputStream {

        /**
         * OpenSSL only lets one thread write at a time, so this is used
         * to make sure we serialize callers of SSL_write. Thread is
         * already expected to have completed handshaking.
         */
        private final Object writeLock = new Object();

        SSLOutputStream() {
        }

        /**
         * Method acts as described in spec for superclass.
         * @see java.io.OutputStream#write(int)
         */
        @Override
        public void write(int oneByte) throws IOException {
            Streams.writeSingleByte(this, oneByte);
        }

        /**
         * Method acts as described in spec for superclass.
         * @see java.io.OutputStream#write(byte[],int,int)
         */
        @Override
        public void write(byte[] buf, int offset, int byteCount) throws IOException {
            BlockGuard.getThreadPolicy().onNetwork();
            checkOpen();
            Arrays.checkOffsetAndCount(buf.length, offset, byteCount);
            if (byteCount == 0) {
                return;
            }

            synchronized (writeLock) {
                synchronized (stateLock) {
                    if (state == STATE_CLOSED) {
                        throw new SocketException("socket is closed");
                    }

                    if (DBG_STATE) assertReadableOrWriteableState();
                }

                NativeCrypto.SSL_write(sslNativePointer, socket.getFileDescriptor$(),
                        OpenSSLSocketImpl.this, buf, offset, byteCount, writeTimeoutMilliseconds);
            }
        }


        public void awaitPendingOps() {
            if (DBG_STATE) {
                synchronized (stateLock) {
                    if (state != STATE_CLOSED) throw new AssertionError("State is: " + state);
                }
            }

            synchronized (writeLock) { }
        }
    }


    @Override
    public SSLSession getSession() {
        if (sslSession == null) {
            try {
                waitForHandshake();
            } catch (IOException e) {
                // return an invalid session with
                // invalid cipher suite of "SSL_NULL_WITH_NULL_NULL"
                return SSLSessionImpl.getNullSession();
            }
        }
        return sslSession;
    }

    @Override
    public SSLSession getHandshakeSession() {
        return handshakeSession;
    }

    @Override
    public void addHandshakeCompletedListener(
            HandshakeCompletedListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Provided listener is null");
        }
        if (listeners == null) {
            listeners = new ArrayList<HandshakeCompletedListener>();
        }
        listeners.add(listener);
    }

    @Override
    public void removeHandshakeCompletedListener(
            HandshakeCompletedListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Provided listener is null");
        }
        if (listeners == null) {
            throw new IllegalArgumentException(
                    "Provided listener is not registered");
        }
        if (!listeners.remove(listener)) {
            throw new IllegalArgumentException(
                    "Provided listener is not registered");
        }
    }

    @Override
    public boolean getEnableSessionCreation() {
        return sslParameters.getEnableSessionCreation();
    }

    @Override
    public void setEnableSessionCreation(boolean flag) {
        sslParameters.setEnableSessionCreation(flag);
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return NativeCrypto.getSupportedCipherSuites();
    }

    @Override
    public String[] getEnabledCipherSuites() {
        return enabledCipherSuites.clone();
    }

    @Override
    public void setEnabledCipherSuites(String[] suites) {
        enabledCipherSuites = NativeCrypto.checkEnabledCipherSuites(suites);
    }

    @Override
    public String[] getSupportedProtocols() {
        return NativeCrypto.getSupportedProtocols();
    }

    @Override
    public String[] getEnabledProtocols() {
        return enabledProtocols.clone();
    }

    @Override
    public void setEnabledProtocols(String[] protocols) {
        enabledProtocols = NativeCrypto.checkEnabledProtocols(protocols);
    }

    /**
     * This method enables session ticket support.
     *
     * @param useSessionTickets True to enable session tickets
     */
    public void setUseSessionTickets(boolean useSessionTickets) {
        this.useSessionTickets = useSessionTickets;
    }

    /**
     * This method enables Server Name Indication
     *
     * @param hostname the desired SNI hostname, or null to disable
     */
    public void setHostname(String hostname) {
        useSni = hostname != null;
        wrappedHost = hostname;
    }

    /**
     * Enables/disables TLS Channel ID for this server socket.
     *
     * <p>This method needs to be invoked before the handshake starts.
     *
     * @throws IllegalStateException if this is a client socket or if the handshake has already
     *         started.

     */
    public void setChannelIdEnabled(boolean enabled) {
        if (getUseClientMode()) {
            throw new IllegalStateException("Client mode");
        }

        synchronized (stateLock) {
            if (state != STATE_NEW) {
                throw new IllegalStateException(
                        "Could not enable/disable Channel ID after the initial handshake has"
                                + " begun.");
            }
        }
        this.channelIdEnabled = enabled;
    }

    /**
     * Gets the TLS Channel ID for this server socket. Channel ID is only available once the
     * handshake completes.
     *
     * @return channel ID or {@code null} if not available.
     *
     * @throws IllegalStateException if this is a client socket or if the handshake has not yet
     *         completed.
     * @throws SSLException if channel ID is available but could not be obtained.
     */
    public byte[] getChannelId() throws SSLException {
        if (getUseClientMode()) {
            throw new IllegalStateException("Client mode");
        }

        synchronized (stateLock) {
            if (state != STATE_READY) {
                throw new IllegalStateException(
                        "Channel ID is only available after handshake completes");
            }
        }
        return NativeCrypto.SSL_get_tls_channel_id(sslNativePointer);
    }

    /**
     * Sets the {@link PrivateKey} to be used for TLS Channel ID by this client socket.
     *
     * <p>This method needs to be invoked before the handshake starts.
     *
     * @param privateKey private key (enables TLS Channel ID) or {@code null} for no key (disables
     *        TLS Channel ID). The private key must be an Elliptic Curve (EC) key based on the NIST
     *        P-256 curve (aka SECG secp256r1 or ANSI X9.62 prime256v1).
     *
     * @throws IllegalStateException if this is a server socket or if the handshake has already
     *         started.
     */
    public void setChannelIdPrivateKey(PrivateKey privateKey) {
        if (!getUseClientMode()) {
            throw new IllegalStateException("Server mode");
        }

        synchronized (stateLock) {
            if (state != STATE_NEW) {
                throw new IllegalStateException(
                        "Could not change Channel ID private key after the initial handshake has"
                                + " begun.");
            }
        }

        if (privateKey == null) {
            this.channelIdEnabled = false;
            this.channelIdPrivateKey = null;
        } else {
            this.channelIdEnabled = true;
            try {
                this.channelIdPrivateKey = OpenSSLKey.fromPrivateKey(privateKey);
            } catch (InvalidKeyException e) {
                // Will have error in startHandshake
            }
        }
    }

    @Override
    public boolean getUseClientMode() {
        return sslParameters.getUseClientMode();
    }

    @Override
    public void setUseClientMode(boolean mode) {
        synchronized (stateLock) {
            if (state != STATE_NEW) {
                throw new IllegalArgumentException(
                        "Could not change the mode after the initial handshake has begun.");
            }
        }
        sslParameters.setUseClientMode(mode);
    }

    @Override
    public boolean getWantClientAuth() {
        return sslParameters.getWantClientAuth();
    }

    @Override
    public boolean getNeedClientAuth() {
        return sslParameters.getNeedClientAuth();
    }

    @Override
    public void setNeedClientAuth(boolean need) {
        sslParameters.setNeedClientAuth(need);
    }

    @Override
    public void setWantClientAuth(boolean want) {
        sslParameters.setWantClientAuth(want);
    }

    @Override
    public void sendUrgentData(int data) throws IOException {
        throw new SocketException("Method sendUrgentData() is not supported.");
    }

    @Override
    public void setOOBInline(boolean on) throws SocketException {
        throw new SocketException("Methods sendUrgentData, setOOBInline are not supported.");
    }

    @Override
    public void setSoTimeout(int readTimeoutMilliseconds) throws SocketException {
        super.setSoTimeout(readTimeoutMilliseconds);
        this.readTimeoutMilliseconds = readTimeoutMilliseconds;
    }

    @Override
    public int getSoTimeout() throws SocketException {
        return readTimeoutMilliseconds;
    }

    /**
     * Note write timeouts are not part of the javax.net.ssl.SSLSocket API
     */
    public void setSoWriteTimeout(int writeTimeoutMilliseconds) throws SocketException {
        this.writeTimeoutMilliseconds = writeTimeoutMilliseconds;

        StructTimeval tv = StructTimeval.fromMillis(writeTimeoutMilliseconds);
        try {
            Libcore.os.setsockoptTimeval(getFileDescriptor$(), SOL_SOCKET, SO_SNDTIMEO, tv);
        } catch (ErrnoException errnoException) {
            throw errnoException.rethrowAsSocketException();
        }
    }

    /**
     * Note write timeouts are not part of the javax.net.ssl.SSLSocket API
     */
    public int getSoWriteTimeout() throws SocketException {
        return writeTimeoutMilliseconds;
    }

    /**
     * Set the handshake timeout on this socket.  This timeout is specified in
     * milliseconds and will be used only during the handshake process.
     */
    public void setHandshakeTimeout(int handshakeTimeoutMilliseconds) throws SocketException {
        this.handshakeTimeoutMilliseconds = handshakeTimeoutMilliseconds;
    }

    @Override
    public void close() throws IOException {
        // TODO: Close SSL sockets using a background thread so they close gracefully.

        SSLInputStream sslInputStream = null;
        SSLOutputStream sslOutputStream = null;

        synchronized (stateLock) {
            if (state == STATE_CLOSED) {
                // close() has already been called, so do nothing and return.
                return;
            }

            int oldState = state;
            state = STATE_CLOSED;

            if (oldState == STATE_NEW) {
                // The handshake hasn't been started yet, so there's no OpenSSL related
                // state to clean up. We still need to close the underlying socket if
                // we're wrapping it and were asked to autoClose.
                closeUnderlyingSocket();

                stateLock.notifyAll();
                return;
            }

            if (oldState != STATE_READY && oldState != STATE_READY_HANDSHAKE_CUT_THROUGH) {
                // If we're in these states, we still haven't returned from startHandshake.
                // We call SSL_interrupt so that we can interrupt SSL_do_handshake and then
                // set the state to STATE_CLOSED. startHandshake will handle all cleanup
                // after SSL_do_handshake returns, so we don't have anything to do here.
                NativeCrypto.SSL_interrupt(sslNativePointer);

                stateLock.notifyAll();
                return;
            }

            stateLock.notifyAll();
            // We've already returned from startHandshake, so we potentially have
            // input and output streams to clean up.
            sslInputStream = is;
            sslOutputStream = os;
        }

        // Don't bother interrupting unless we have something to interrupt.
        if (sslInputStream != null || sslOutputStream != null) {
            NativeCrypto.SSL_interrupt(sslNativePointer);
        }

        // Wait for the input and output streams to finish any reads they have in
        // progress. If there are no reads in progress at this point, future reads will
        // throw because state == STATE_CLOSED
        if (sslInputStream != null) {
            sslInputStream.awaitPendingOps();
        }
        if (sslOutputStream != null) {
            sslOutputStream.awaitPendingOps();
        }

        shutdownAndFreeSslNative();
    }

    private void shutdownAndFreeSslNative() throws IOException {
        try {
            BlockGuard.getThreadPolicy().onNetwork();
            NativeCrypto.SSL_shutdown(sslNativePointer, socket.getFileDescriptor$(),
                    this);
        } catch (IOException ignored) {
            /*
            * Note that although close() can throw
            * IOException, the RI does not throw if there
            * is problem sending a "close notify" which
            * can happen if the underlying socket is closed.
            */
        } finally {
            free();
            closeUnderlyingSocket();
        }
    }

    private void closeUnderlyingSocket() throws IOException {
        if (socket != this) {
            if (autoClose && !socket.isClosed()) {
                socket.close();
            }
        } else {
            if (!super.isClosed()) {
                super.close();
            }
        }
    }

    private void free() {
        if (sslNativePointer == 0) {
            return;
        }
        NativeCrypto.SSL_free(sslNativePointer);
        sslNativePointer = 0;
        guard.close();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            /*
             * Just worry about our own state. Notably we do not try and
             * close anything. The SocketImpl, either our own
             * PlainSocketImpl, or the Socket we are wrapping, will do
             * that. This might mean we do not properly SSL_shutdown, but
             * if you want to do that, properly close the socket yourself.
             *
             * The reason why we don't try to SSL_shutdown, is that there
             * can be a race between finalizers where the PlainSocketImpl
             * finalizer runs first and closes the socket. However, in the
             * meanwhile, the underlying file descriptor could be reused
             * for another purpose. If we call SSL_shutdown, the
             * underlying socket BIOs still have the old file descriptor
             * and will write the close notify to some unsuspecting
             * reader.
             */
            if (guard != null) {
                guard.warnIfOpen();
            }
            free();
        } finally {
            super.finalize();
        }
    }

    @Override
    public FileDescriptor getFileDescriptor$() {
        if (socket == this) {
            return super.getFileDescriptor$();
        } else {
            return socket.getFileDescriptor$();
        }
    }

    /**
     * Returns the protocol agreed upon by client and server, or null if no
     * protocol was agreed upon.
     */
    public byte[] getNpnSelectedProtocol() {
        return NativeCrypto.SSL_get_npn_negotiated_protocol(sslNativePointer);
    }

    /**
     * Returns the protocol agreed upon by client and server, or {@code null} if
     * no protocol was agreed upon.
     */
    public byte[] getAlpnSelectedProtocol() {
        return NativeCrypto.SSL_get0_alpn_selected(sslNativePointer);
    }

    /**
     * Sets the list of protocols this peer is interested in. If null no
     * protocols will be used.
     *
     * @param npnProtocols a non-empty array of protocol names. From
     *     SSL_select_next_proto, "vector of 8-bit, length prefixed byte
     *     strings. The length byte itself is not included in the length. A byte
     *     string of length 0 is invalid. No byte string may be truncated.".
     */
    public void setNpnProtocols(byte[] npnProtocols) {
        if (npnProtocols != null && npnProtocols.length == 0) {
            throw new IllegalArgumentException("npnProtocols.length == 0");
        }
        this.npnProtocols = npnProtocols;
    }

    /**
     * Sets the list of protocols this peer is interested in. If the list is
     * {@code null}, no protocols will be used.
     *
     * @param alpnProtocols a non-empty array of protocol names. From
     *            SSL_select_next_proto, "vector of 8-bit, length prefixed byte
     *            strings. The length byte itself is not included in the length.
     *            A byte string of length 0 is invalid. No byte string may be
     *            truncated.".
     */
    public void setAlpnProtocols(byte[] alpnProtocols) {
        if (alpnProtocols != null && alpnProtocols.length == 0) {
            throw new IllegalArgumentException("alpnProtocols.length == 0");
        }
        this.alpnProtocols = alpnProtocols;
    }

    /** Key type: RSA. */
    private static final String KEY_TYPE_RSA = "RSA";

    /** Key type: DSA. */
    private static final String KEY_TYPE_DSA = "DSA";

    /** Key type: Diffie-Hellman with RSA signature. */
    private static final String KEY_TYPE_DH_RSA = "DH_RSA";

    /** Key type: Diffie-Hellman with DSA signature. */
    private static final String KEY_TYPE_DH_DSA = "DH_DSA";

    /** Key type: Elliptic Curve. */
    private static final String KEY_TYPE_EC = "EC";

    /** Key type: Eliiptic Curve with ECDSA signature. */
    private static final String KEY_TYPE_EC_EC = "EC_EC";

    /** Key type: Eliiptic Curve with RSA signature. */
    private static final String KEY_TYPE_EC_RSA = "EC_RSA";

    /**
     * Returns key type constant suitable for calling X509KeyManager.chooseServerAlias or
     * X509ExtendedKeyManager.chooseEngineServerAlias. Returns {@code null} for anonymous key
     * exchanges.
     */
    private static String getServerKeyType(long sslCipherNative) throws SSLException {
        int algorithm_mkey = NativeCrypto.get_SSL_CIPHER_algorithm_mkey(sslCipherNative);
        int algorithm_auth = NativeCrypto.get_SSL_CIPHER_algorithm_auth(sslCipherNative);
        switch (algorithm_mkey) {
            case NativeCrypto.SSL_kRSA:
                return KEY_TYPE_RSA;
            case NativeCrypto.SSL_kEDH:
                switch (algorithm_auth) {
                    case NativeCrypto.SSL_aDSS:
                        return KEY_TYPE_DSA;
                    case NativeCrypto.SSL_aRSA:
                        return KEY_TYPE_RSA;
                    case NativeCrypto.SSL_aNULL:
                        return null;
                }
                break;
            case NativeCrypto.SSL_kECDHr:
                return KEY_TYPE_EC_RSA;
            case NativeCrypto.SSL_kECDHe:
                return KEY_TYPE_EC_EC;
            case NativeCrypto.SSL_kEECDH:
                switch (algorithm_auth) {
                    case NativeCrypto.SSL_aECDSA:
                        return KEY_TYPE_EC_EC;
                    case NativeCrypto.SSL_aRSA:
                        return KEY_TYPE_RSA;
                    case NativeCrypto.SSL_aNULL:
                        return null;
                }
                break;
        }

        throw new SSLException("Unsupported key exchange. "
                + "mkey: 0x" + Long.toHexString(algorithm_mkey & 0xffffffffL)
                + ", auth: 0x" + Long.toHexString(algorithm_auth & 0xffffffffL));
    }

    /**
     * Similar to getServerKeyType, but returns value given TLS
     * ClientCertificateType byte values from a CertificateRequest
     * message for use with X509KeyManager.chooseClientAlias or
     * X509ExtendedKeyManager.chooseEngineClientAlias.
     */
    public static String getClientKeyType(byte keyType) {
        // See also http://www.ietf.org/assignments/tls-parameters/tls-parameters.xml
        switch (keyType) {
            case NativeCrypto.TLS_CT_RSA_SIGN:
                return KEY_TYPE_RSA; // RFC rsa_sign
            case NativeCrypto.TLS_CT_DSS_SIGN:
                return KEY_TYPE_DSA; // RFC dss_sign
            case NativeCrypto.TLS_CT_RSA_FIXED_DH:
                return KEY_TYPE_DH_RSA; // RFC rsa_fixed_dh
            case NativeCrypto.TLS_CT_DSS_FIXED_DH:
                return KEY_TYPE_DH_DSA; // RFC dss_fixed_dh
            case NativeCrypto.TLS_CT_ECDSA_SIGN:
                return KEY_TYPE_EC; // RFC ecdsa_sign
            case NativeCrypto.TLS_CT_RSA_FIXED_ECDH:
                return KEY_TYPE_EC_RSA; // RFC rsa_fixed_ecdh
            case NativeCrypto.TLS_CT_ECDSA_FIXED_ECDH:
                return KEY_TYPE_EC_EC; // RFC ecdsa_fixed_ecdh
            default:
                return null;
        }
    }

    @Override
    public SSLParameters getSSLParameters() {
        SSLParameters params = super.getSSLParameters();
        params.setEndpointIdentificationAlgorithm(
                sslParameters.getEndpointIdentificationAlgorithm());
        return params;
    }

    @Override
    public void setSSLParameters(SSLParameters p) {
        super.setSSLParameters(p);
        sslParameters.setEndpointIdentificationAlgorithm(p.getEndpointIdentificationAlgorithm());
    }
}
