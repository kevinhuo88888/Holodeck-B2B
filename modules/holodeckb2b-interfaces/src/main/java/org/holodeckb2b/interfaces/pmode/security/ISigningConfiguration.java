/**
 * Copyright (C) 2014 The Holodeck B2B Team, Sander Fieten
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.holodeckb2b.interfaces.pmode.security;

import org.holodeckb2b.interfaces.config.IConfiguration;

/**
 * Defines the configuration of a WSS Signature contained in the security header of the ebMS message. Depending on
 * the direction (incoming or outgoing) of the message the information is used to create or validate the Signature in
 * the WSS header.
 * <p>The settings defined by the interface correspond with the P-Mode parameter group
 * <b>PMode[1].Security.X509.Sign</b>.
 * <p>NOTE: Most settings only apply to outgoing messages that should be signed. WSS Signatures included in incoming
 * messages are always validated, regardless whether a <code>ISigningConfiguration</code> is given for sender of the
 * message. If however a <code>ISigningConfiguration</code> is provided Holodeck B2B will check that the expected
 * certifcate is used and if it is not revoked (depending on the revocation setting, {@link #enableRevocationCheck()}).
 *
 * @author Sander Fieten (sander at holodeck-b2b.org)
 * @author Bram Bakx <bram at holodeck-b2b.org>
 */
public interface ISigningConfiguration {

    /**
     * Gets the Java keystore <i>alias</i> that identifies the X509 certificate that should be used for signing.
     * <p>The current implementation of Holodeck B2B uses Java keystores to store certificates. Two keystores are used
     * to storing private and public certificates and another for storing CA certificates (the trust store). Depending
     * what this configuration applies to the certificate must exist in either the private (when signing outgoing
     * messages) or public (when validating incoming messages) keystore.
     *
     * @return  The alias that identifies the certificate to use for signing.
     */
    public String getKeystoreAlias();

    /**
     * Gets the password to access the private key hold by the certificate. Only applies to configurations that are
     * used to sign messages.
     * <p>Current implementation of Holodeck B2B requires that result is the password in clear text. Future version may
     * change this to get better secured passwords.
     *
     * @return  The password to get access to the private key
     */
    public String getCertificatePassword();

    /**
     * Gets the method of referencing the certificate used for signing. Section 3.2 of the WS-Security X.509 Certificate
     * Token Profile Version 1.1.1 specification defines the three options that are allowed for referencing the
     * certificate.
     * <p>NOTE 1: This setting only applies to signatures that are generated by Holodeck B2B,i.e. for outgoing messages.
     * When the signature of an incoming message is validated the reference included in the message will be used to look
     * up the certificate regardless of the used reference method.
     * <p>NOTE 2: When not specified in the P-Mode Holodeck B2B will reference the certificate using its issuer and
     * serial number (as specified in section 3.2.3 of WS-Sec X.509 CTP).
     *
     * @return  The method to be used for referencing the certificate, or<br>
     *          <code>null</code> if no method is specified
     */
    public X509ReferenceType getKeyReferenceMethod();

    /**
     * Indicates whether the complete certificate path must be included in the binary security token that contains the
     * certificate used for signing the message. This setting only applies when Holodeck B2B creates the signature,i.e.
     * is the sender of the signed message. See section 3.1 of the WS-Security X.509 Certificate Token Profile Version
     * 1.1.1 specification for more information on the binary security token types.
     * <p>By including the certificate path or chain in the binary security token the receiver of message does not need
     * to know the end entity's certificate but can rely on the trust from a certificate authority. This makes
     * certificate management much simpler as only the CA certificates need to be known on the receiving MSH.
     * <p>Because the certificate path can only be included in a binary security token, this option can only be used
     * when the key reference method is set to <i>BSTReference</i>.
     * <p>Note that the receiving MSH must also be configured to accept <b>and use</b> a certificate path for validation
     * of the signature. Therefore the default is to only include the end-entity certificate in the WS-Secuirty header.
     * <p>NOTE: The certificate loaded in the keystore MUST already contain the complete certificate path, Holodeck B2B
     * will not try to construct the path based on the end-entity certificate!

     * @return  <code>Boolean.TRUE</code> if the complete certificate path should be included in the binary security
     *          token,only applicable when {@link #getKeyReferenceMethod()}=={@link X509ReferenceType#BSTReference},<br>
     *          <code>Boolean.FALSE</code> or <code>null</code>if only the end entity's certificate should be included.
     */
    public Boolean includeCertificatePath();

    /**
     * Indicates whether the possible revocation of a certificate must be checked. Only applies to configuration of
     * signatures for incoming messages.
     * <p>NOTE 1: When an error occurs during the revocation check the certificate will be treated as invalid resulting
     * in rejection of the complete ebMS message and all message units contained in it. Therefor the revocation check
     * should only be enabled if the PKI infrastructure works correctly.
     * <p>NOTE 2: Whether the revocation must be checked is an optional setting in the P-Mode. If not configured in the
     * P-Mode the global setting will be used.
     *
     * @return <code>Boolean.TRUE</code> if certificate should be checked for revocation,<br>
     *         <code>Boolean.FALSE</code> if certificate should not be checked for revocation,<br>
     *         <code>null</code> if global setting should be used.
     * @see IConfiguration#shouldCheckCertificateRevocation()
     */
    public Boolean enableRevocationCheck();

    /**
     * Gets the algorithm that should be used to calculate the signature value. Only applies to configuration of
     * signatures for outgoing messages.
     *
     * @return  The identifier of the algorithm to use for signing, as specified in section 6.4 of the XML Signature
     *          Syntax and Processing specification Version 1.1 (<a href="http://www.w3.org/TR/xmldsig-core1/">
     *          http://www.w3.org/TR/xmldsig-core1/</a>).
     */
    public String getSignatureAlgorithm();

    /**
     * Gets the algorithm that should be used to calculate the hash value. Only applies to configuration of
     * signatures for outgoing messages.
     *
     * @return  The identifier of the algorithm to use for hashing, as specified in section 6.2 of the XML Signature
     *          Syntax and Processing specification Version 1.1 (<a href="http://www.w3.org/TR/xmldsig-core1/">
     *          http://www.w3.org/TR/xmldsig-core1/</a>).
     */
    public String getHashFunction();
}
