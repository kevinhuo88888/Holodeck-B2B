/*
 * Copyright (C) 2014 The Holodeck B2B Team, Sander Fieten
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.holodeckb2b.security.handlers;

import java.util.List;
import java.util.Properties;
import javax.security.auth.callback.CallbackHandler;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.MessageContext;
import org.apache.commons.logging.Log;
import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.dom.WSSConfig;
import org.apache.wss4j.dom.handler.HandlerAction;
import org.apache.wss4j.dom.handler.RequestData;
import org.apache.wss4j.dom.handler.WSHandler;
import org.apache.wss4j.dom.handler.WSHandlerConstants;
import org.apache.wss4j.dom.util.WSSecurityUtil;
import org.holodeckb2b.common.general.Constants;
import org.holodeckb2b.common.handler.BaseHandler;
import org.holodeckb2b.common.security.IEncryptionConfiguration;
import org.holodeckb2b.common.security.ISigningConfiguration;
import org.holodeckb2b.common.security.IUsernameTokenConfiguration;
import org.holodeckb2b.ebms3.constants.SecurityConstants;
import org.holodeckb2b.ebms3.util.Axis2Utils;
import org.holodeckb2b.security.callbackhandlers.AttachmentCallbackHandler;
import org.holodeckb2b.security.callbackhandlers.PasswordCallbackHandler;
import org.holodeckb2b.security.util.SecurityUtils;
import org.w3c.dom.Document;

/**
 * Is the <i>OUT_FLOW</i> handler that creates the WS-Security headers in the outgoing message. It uses the WSS4J 
 * library for the actual work of adding the headers.
 * <p>The handler can add two security headers, one default and one targeted to the "ebms" role/actor. The latter can
 * only contain a <code>wsse:UsernameToken</code> element for authentication and authorization purposes (see section 
 * 7.10 of the ebMS v3 Core Specification). The default can also contain the signature and encrypted data of the message
 * <p>Whether security headers must be create should be indicated by the message context property {@link 
 * SecurityConstants#ADD_SECURITY_HEADERS}. If it contains the <code>Boolean.TRUE</code> the headers will be created.
 * The configuration for the information to be included in the headers should also be specified in message properties
 * as shown in the table below:
 * <table border="1">
 * <tr><td>Property key</td><td>Configuration interface</td><td>Contains configuration for</td></tr>
 * <tr><td>{@link SecurityConstants#EBMS_USERNAMETOKEN}</td>
 *              <td>{@link IUsernameTokenConfiguration}</td>
 *              <td>The <code>UsernameToken</code> targeted at the <i>ebms</i> role</td></tr>
 * <tr><td>{@link SecurityConstants#DEFAULT_USERNAMETOKEN}</td
 *              <td>{@link IUsernameTokenConfiguration}</td>
 *              <td>The <code>UsernameToken</code> targeted at the <i>default</i> role</td></tr>
 * <tr><td>{@link SecurityConstants#SIGNATURE}</td>
 *              <td>{@link ISigningConfiguration}</td>
 *              <td>The <code>Signature</code> to create in the <i>default</i> header</td></tr>
 * </table>
 * 
 * @author Sander Fieten <sander at holodeck-b2b.org>
 */
public class CreateWSSHeaders extends BaseHandler {
    
    protected static final String WSS4J_PART_EBMS_HEADER = "{}{" + Constants.EBMS3_NS_URI + "}Messaging;";
    
    protected static final String WSS4J_PART_S11_BODY = "{}{http://schemas.xmlsoap.org/soap/envelope/}Body;";
    protected static final String WSS4J_PART_S12_BODY = "{}{http://www.w3.org/2003/05/soap-envelope}Body;";
    
    protected static final String WSS4J_PART_UT = 
                "{}{http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd}UsernameToken;";
    
    protected static final String WSS4J_PART_ATTACHMENTS = "{}cid:Attachments;";
    
    @Override
    protected byte inFlows() {
        return OUT_FLOW | OUT_FAULT_FLOW;
    }

    @Override
    protected InvocationResponse doProcessing(MessageContext mc) throws AxisFault {
        CreateWSSHeaders.WSSSendHandler processor = null;
        
        // Check if security headers must be added
        Boolean addHeaders = (Boolean) mc.getProperty(SecurityConstants.ADD_SECURITY_HEADERS);
        if (addHeaders == null || !addHeaders) {
            log.debug("No security headers to add, skip processing");
            return InvocationResponse.CONTINUE;
        }
        
        // Convert the SOAP Envelope to standard DOM representation as this is required by the security processing
        // libraries
        Document domEnvelope = Axis2Utils.convertToDOM(mc);
        
        if (domEnvelope == null) {
            log.error("Converting the SOAP envelope to DOM representation failed");            
            return InvocationResponse.CONTINUE;
        }
        
        try {
            // Create security header processor
            processor = new CreateWSSHeaders.WSSSendHandler(mc, domEnvelope, log);
        } catch (WSSecurityException ex) {
            log.error("Setting up the security processor failed! Details: " + ex.getMessage());            
            return InvocationResponse.CONTINUE;
        }
        
        // Set up the message context properties specific for the header targeted to ebms role. This can only contain
        // a username token
        IUsernameTokenConfiguration  utConfig = (IUsernameTokenConfiguration) 
                                                                  mc.getProperty(SecurityConstants.EBMS_USERNAMETOKEN);
        
        if (utConfig != null) {
            log.debug("A UsernameToken element must be added to the security header targeted to ebms role");
            // Create a password callback handler to hand over the password
            PasswordCallbackHandler pwdCBHandler = new PasswordCallbackHandler();
            mc.setProperty(WSHandlerConstants.PW_CALLBACK_REF, pwdCBHandler);
    
            setupUsernameToken(mc, utConfig, pwdCBHandler);            
            try {
                // The security header targeted to the ebms actor should only contain a Username token. 
                log.debug("Add the WSS header targeted to ebms role");
                processor.createSecurityHeader(SecurityConstants.EBMS_WSS_HEADER, WSHandlerConstants.USERNAME_TOKEN);            
                log.debug("Added the WSS header targeted to ebms role");
            } catch (WSSecurityException wse) {
                log.error("Creating the WSS header for the ebms role failed!" 
                            + "\n\tDetails: " + wse.getMessage() 
                            + "\n\tRoot cause:" + wse.getCause().getMessage());
                //@todo: What's next? 
            }
            utConfig = null; // reset usernametoken config
        }

        // Set up the message context properties for the default WSS header. This header can also include signing and 
        // encryption
        // The actions that need to be executed
        String  actions = "";
        // Create a password callback handler to hand over the password
        PasswordCallbackHandler pwdCBHandler = new PasswordCallbackHandler();
        mc.setProperty(WSHandlerConstants.PW_CALLBACK_REF, pwdCBHandler);

        // Check if a UsernameToken element should be added
        utConfig = (IUsernameTokenConfiguration) mc.getProperty(SecurityConstants.DEFAULT_USERNAMETOKEN);
        if (utConfig != null) {
            log.debug("A UsernameToken element must be added to the security header targeted to ebms role");
            // Add UT action
            actions = WSHandlerConstants.USERNAME_TOKEN;
            // Set up message context
            setupUsernameToken(mc, utConfig, pwdCBHandler);            
        }
        
        ISigningConfiguration signatureCfg = (ISigningConfiguration) mc.getProperty(SecurityConstants.SIGNATURE);
        if (signatureCfg != null) {
            log.debug("The message must be signed, set up signature configuration");
            // Add Signature action
            actions += " " + WSHandlerConstants.SIGNATURE;
            // Set up message context
            setupSignature(mc, signatureCfg, pwdCBHandler);
        }
        
        IEncryptionConfiguration encryptCfg = (IEncryptionConfiguration) mc.getProperty(SecurityConstants.ENCRYPTION);
        if (encryptCfg != null) {
            log.debug("The message must be encrypted, set up encryption configuration");
            // Add encryption action
            actions += " " + WSHandlerConstants.ENCRYPT;
            // Set up message context
            setupEncryption(mc, encryptCfg, pwdCBHandler);
        }
        
        try {
            log.debug("Add the default WSS header");
            processor.createSecurityHeader(null, actions);            
            log.debug("Added the default WSS header");
        } catch (WSSecurityException wse) {
            log.error("Creating the default WSS header failed!" 
                        + "\n\tDetails: " + wse.getMessage() 
                        + "\n\tRoot cause:" + wse.getCause().getMessage());
            //@todo: What's next? 
        }
        
        // Convert the processed SOAP envelope back to the Axiom representation for further processing
        SOAPEnvelope SOAPenv = Axis2Utils.convertToAxiom(domEnvelope);
        
        if (SOAPenv == null) {
            log.error("Converting the SOAP envelope to Axiom representation failed");
        } else {
            mc.setEnvelope(SOAPenv);
            log.debug("Security header(s) successfully added");
        }
        
        return InvocationResponse.CONTINUE;
    }

    /**
     * Sets the message context properties for adding a UsernameToken to the security header.
     * <p>Because other elements that need to be added to the header may also require a password the password callback
     * handler is not created in this method, but shared for the header.
     * 
     * @param mc            The {@link MessageContext} to set up
     * @param utConfig      The configuration for the username token
     * @param pwdCBHandler  The {@link PasswordCallbackHandler} to use for handing over the password to WSS4J library
     */
    private void setupUsernameToken(MessageContext mc, IUsernameTokenConfiguration utConfig, PasswordCallbackHandler pwdCBHandler) {
        mc.setProperty(WSHandlerConstants.USER, utConfig.getUsername());
        mc.setProperty(WSHandlerConstants.ADD_USERNAMETOKEN_CREATED, Boolean.toString(utConfig.includeCreated()));
        mc.setProperty(WSHandlerConstants.ADD_USERNAMETOKEN_NONCE, Boolean.toString(utConfig.includeNonce()));

        mc.setProperty(WSHandlerConstants.PASSWORD_TYPE, 
                                        utConfig.getPasswordType() == IUsernameTokenConfiguration.PasswordType.DIGEST ? 
                                        WSConstants.PW_DIGEST : WSConstants.PW_TEXT);

        pwdCBHandler.addUser(utConfig.getUsername(), utConfig.getPassword());
    }

    /**
     * Sets the message context properties for adding a Signature to the security header.
     * <p>Because other elements that need to be added to the header may also require a password the password callback
     * handler is not created in this method, but shared for the header.
     * 
     * @param mc            The {@link MessageContext} to set up
     * @param sigConfig     The configuration for creating the signature
     * @param pwdCBHandler  The {@link PasswordCallbackHandler} to use for handing over the password to WSS4J library
     */
    private void setupSignature(MessageContext mc, ISigningConfiguration sigCfg, PasswordCallbackHandler pwdCBHandler) {
        // Set up crypto engine
        Properties sigProperties = SecurityUtils.createCryptoConfig(SecurityUtils.CertType.priv);
        mc.setProperty(WSHandlerConstants.SIG_PROP_REF_ID, "" + sigProperties.hashCode());
        mc.setProperty("" + sigProperties.hashCode(), sigProperties);

        // Set up signing config
        // AS4 requires that the ebMS message header (eb:Messaging element) and SOAP Body are signed 
        mc.setProperty(WSHandlerConstants.SIGNATURE_PARTS, WSS4J_PART_EBMS_HEADER 
                                                                            + (mc.isSOAP11() ? WSS4J_PART_S11_BODY :
                                                                                               WSS4J_PART_S12_BODY));
        // And if there are attachments also the attachments. Whether UsernameToken elements in the security header
        // should be signed is not specified. But to prevent manipulation Holodeck B2B includes them in the signature
        mc.setProperty(WSHandlerConstants.OPTIONAL_SIGNATURE_PARTS, WSS4J_PART_UT + WSS4J_PART_ATTACHMENTS);
        
        // The alias of the certificate to use for signing
        mc.setProperty(WSHandlerConstants.SIGNATURE_USER, sigCfg.getKeystoreAlias());
        // The password to access the certificate in the keystore
        pwdCBHandler.addUser(sigCfg.getKeystoreAlias(), sigCfg.getCertificatePassword());
        
        // How should certificate be referenced in header?
        mc.setProperty(WSHandlerConstants.SIG_KEY_ID, SecurityUtils.getWSS4JX509KeyId(sigCfg.getKeyReferenceMethod()));
        // Algorithms to use
        mc.setProperty(WSHandlerConstants.SIG_DIGEST_ALGO, sigCfg.getHashFunction());
        mc.setProperty(WSHandlerConstants.SIG_ALGO, sigCfg.getSignatureAlgorithm());        
    }

    
    /**
     * Sets the message context properties for adding encryption to the security header.
     * <p>Because other elements that need to be added to the header may also require a password the password callback
     * handler is not created in this method, but shared for the header.
     * 
     * @param mc            The {@link MessageContext} to set up
     * @param sigConfig     The configuration for creating the signature
     * @param pwdCBHandler  The {@link PasswordCallbackHandler} to use for handing over the password to WSS4J library
     */
    private void setupEncryption(MessageContext mc, IEncryptionConfiguration encCfg, PasswordCallbackHandler pwdCBHandler) {
        // Set up crypto engine
        Properties encProperties = SecurityUtils.createCryptoConfig(SecurityUtils.CertType.pub);
        mc.setProperty(WSHandlerConstants.ENC_PROP_REF_ID, "" + encProperties.hashCode());
        mc.setProperty("" + encProperties.hashCode(), encProperties);

        // Set up encryption config
        // AS4 requires that only the payloads are encrypted. Although AS4 only requires encryption of the SOAP Body if
        // if it contains a payload we just encrypt it. 
        mc.setProperty(WSHandlerConstants.ENCRYPTION_PARTS, (mc.isSOAP11() ? WSS4J_PART_S11_BODY :
                                                                             WSS4J_PART_S12_BODY));
        
        // And if there are attachments also the attachments must be encrypted. 
        mc.setProperty(WSHandlerConstants.OPTIONAL_ENCRYPTION_PARTS, WSS4J_PART_ATTACHMENTS);
        
        // The alias of the certificate to use for encryption
        mc.setProperty(WSHandlerConstants.ENCRYPTION_USER, encCfg.getKeystoreAlias());
        
        // How should certificate be referenced in header ?
        mc.setProperty(WSHandlerConstants.ENC_KEY_ID, SecurityUtils.getWSS4JX509KeyId(encCfg.getKeyReferenceMethod()));
        
        // Symmetric encryption algorithms to use
        mc.setProperty(WSHandlerConstants.ENC_SYM_ALGO, encCfg.getAlgorithm());
        
        // note: if ENC_KEY_TRANSPORT is equal to RSA-OAEP  
        // then ENC_DIGEST_ALGO and ENC_MFG_ALGO may be set
                
        if (WSConstants.KEYTRANSPORT_RSAOEP_XENC11.equalsIgnoreCase(encCfg.getKeyTransport().getAlgorithm())) {
            
            //
            if (!"".equals(encCfg.getKeyTransport().getDigestAlgorithm()) &&
                !"".equals(encCfg.getKeyTransport().getMGFAlgorithm())    ) {
                
                // Digest algorithm for key transport
                mc.setProperty(WSHandlerConstants.ENC_DIGEST_ALGO, encCfg.getKeyTransport().getDigestAlgorithm());
        
                // Encryption mgf algorithm for key transport
                mc.setProperty(WSHandlerConstants.ENC_MGF_ALGO, encCfg.getKeyTransport().getMGFAlgorithm());
            }
                
        }
        
        // Key transport algorithm 
        mc.setProperty(WSHandlerConstants.ENC_KEY_TRANSPORT, encCfg.getKeyTransport().getAlgorithm());
        
    }    
    
    /**
     * Is the inner class that does the actual processing of the WS Security header. It is based on {@link WSHandler}
     * provided by the Apache WSS4J library. Because the overall class already extends {@link BaseHandler} the inner
     * class construct is used. 
     */
    class WSSSendHandler extends WSHandler {
        /**
         * Logging facility
         */
        private Log log;
        /**
         * The current message context
         */
        private MessageContext msgCtx;
        /**
         * The WSS4J security engine configuration
         */
        private WSSConfig wssConfig;
        /**
         * The DOM representation of the SOAP envelope
         */
        private Document domEnvelope;
        /**
         * Callback handler that provides access to the SOAP attachments
         */
        private CallbackHandler attachmentCBHandler;
        
        /**
         * Creates a WSSSendHandler for creating the security headers in the given message.
         *
         * @param mc    The {@link MessageContext} for the message
         * @param doc   The standard DOM representation of the SOAP Envelope of the message
         * @param log   The log to use. We use the log of the handler to hide this class.
         */
        WSSSendHandler(MessageContext mc, Document doc, Log handlerLog) throws WSSecurityException {
            this.msgCtx = mc;
            this.log = handlerLog;
            this.domEnvelope = doc;
            
            log.debug("Set up security engine configuration");
            wssConfig = WSSConfig.getNewInstance();
            attachmentCBHandler = new AttachmentCallbackHandler(msgCtx);
        }
        
        @Override
        public Object getOption(String string) {
            return msgCtx.getProperty(string);
        }

        @Override
        public Object getProperty(Object o, String string) {
            return getOption(string);
        }

        @Override
        public void setProperty(Object o, String string, Object o1) {
            msgCtx.setProperty(string, o1);
        }

        @Override
        public String getPassword(Object o) {
            return null;
        }

        @Override
        public void setPassword(Object o, String string) {
        }

        void createSecurityHeader(String actor, String actions) throws WSSecurityException {           
            log.debug("Check actions to perform");
            if (actions == null || actions.isEmpty()) {
                log.info("No security actions specified!");
                return;
            }
            List<HandlerAction> actionList = WSSecurityUtil.decodeHandlerAction(actions.trim(), wssConfig);
            if (actionList == null || actionList.isEmpty()) {
                log.info("No security actions specified!");
                return;
            }
            
            log.debug("Configure security processing environment");
            msgCtx.setProperty(WSHandlerConstants.ACTOR, actor);
            RequestData reqData = new RequestData();
            reqData.setWssConfig(wssConfig);
            reqData.setMsgContext(msgCtx);
            // Register callback handler for attachments
            reqData.setAttachmentCallbackHandler(this.attachmentCBHandler);
            
            // Check if we need a username (for UsernameToken or Signature)
            boolean userNameRequired = false;
            for (HandlerAction handlerAction : actionList) {
                if ((handlerAction.getAction() == WSConstants.SIGN
                    || handlerAction.getAction() == WSConstants.UT
                    || handlerAction.getAction() == WSConstants.UT_SIGN)
                    && (handlerAction.getActionToken() == null
                        || handlerAction.getActionToken().getUser() == null)) {
                    userNameRequired = true;
                    break;
                }
            }            
            if (userNameRequired) {
                log.debug("A user name is needed because a username token or signature has to be inserted");
                String userName = (String) getOption(WSHandlerConstants.USER);
                if (userName == null || userName.isEmpty())
                    userName = (String) getOption(WSHandlerConstants.SIGNATURE_USER);
                log.debug("Username for creating the " + actor + " WSS header is set to " + userName);
                
                if (userName == null || userName.isEmpty()) {
                    // We need a username but don't have one :-(
                    log.error("Required username for creating the WSS header is missing!");
                    //throw new AxisFault("NO_USERNAME");
                } else
                    reqData.setUsername(userName);
            }
            
            // Are we processing a request or response?
            boolean isRequest = isInFlow(INITIATOR);
             
            log.debug("Create the \"" + actor + "\" WSS headers for this " + (isRequest ? "request." : "response."));
            doSenderAction(domEnvelope, reqData, actionList, isRequest);
            log.debug("WSS header created.");
        }

    }
}