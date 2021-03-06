// Copyright (C) 2015 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.gpg;

import static com.google.gerrit.gpg.PublicKeyStore.keyIdToString;
import static com.google.gerrit.gpg.PublicKeyStore.keyToString;

import org.bouncycastle.bcpg.SignatureSubpacket;
import org.bouncycastle.bcpg.SignatureSubpacketTags;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checker for GPG public keys for use in a push certificate. */
public class PublicKeyChecker {
  // https://tools.ietf.org/html/rfc4880#section-5.2.3.13
  private static final int COMPLETE_TRUST = 120;

  private final Map<Long, Fingerprint> trusted;
  private final int maxTrustDepth;

  /** Create a new checker that does not check the web of trust. */
  public PublicKeyChecker() {
    this(0, null);
  }

  /**
   * @param maxTrustDepth maximum depth to search while looking for a trusted
   *     key.
   * @param trusted ultimately trusted key fingerprints; may not be empty. If
   *     null, disable web-of-trust checks.
   */
  public PublicKeyChecker(int maxTrustDepth, Collection<Fingerprint> trusted) {
    if (trusted != null) {
      if (maxTrustDepth <= 0) {
        throw new IllegalArgumentException(
            "maxTrustDepth must be positive, got: " + maxTrustDepth);
      }
      if (trusted.isEmpty()) {
        throw new IllegalArgumentException("at least one trusted key required");
      }
      this.trusted = new HashMap<>();
      for (Fingerprint fp : trusted) {
        this.trusted.put(fp.getId(), fp);
      }
    } else {
      this.trusted = null;
    }
    this.maxTrustDepth = maxTrustDepth;
  }

  /**
   * Check a public key, including its web of trust.
   *
   * @param key the public key.
   * @param store a store to read public keys from for trust checks. If this
   *     store is not configured for web-of-trust checks, this argument is
   *     ignored.
   * @return the result of the check.
   */
  public final CheckResult check(PGPPublicKey key, PublicKeyStore store) {
    if (trusted == null) {
      return check(key);
    } else if (store == null) {
      throw new IllegalArgumentException(
          "PublicKeyStore required for web of trust checks");
    }
    return check(key, store, 0, true, new HashSet<Fingerprint>());
  }

  /**
   * Check only a public key, not including its web of trust.
   *
   * @param key the public key.
   * @return the result of the check.
   */
  public final CheckResult check(PGPPublicKey key) {
    return check(key, null, 0, false, null);
  }

  /**
   * Perform custom checks.
   * <p>
   * Default implementation does nothing, but may be overridden by subclasses.
   *
   * @param key the public key.
   * @param problems list to which any problems should be added.
   */
  public void checkCustom(PGPPublicKey key, List<String> problems) {
    // Default implementation does nothing.
  }

  private CheckResult check(PGPPublicKey key, PublicKeyStore store, int depth,
      boolean expand, Set<Fingerprint> seen) {
    List<String> problems = new ArrayList<>();
    if (key.isRevoked()) {
      // TODO(dborowitz): isRevoked is overeager:
      // http://www.bouncycastle.org/jira/browse/BJB-45
      problems.add("Key is revoked");
    }

    long validSecs = key.getValidSeconds();
    if (validSecs != 0) {
      long createdSecs = key.getCreationTime().getTime() / 1000;
      long nowSecs = System.currentTimeMillis() / 1000;
      if (nowSecs - createdSecs > validSecs) {
        problems.add("Key is expired");
      }
    }
    checkCustom(key, problems);

    CheckResult trustResult = checkWebOfTrust(key, store, depth, seen);
    if (expand) {
      problems.addAll(trustResult.getProblems());
    } else if (!trustResult.isOk()) {
      problems.add("Key is not trusted");
    }
    return new CheckResult(problems);
  }

  private CheckResult checkWebOfTrust(PGPPublicKey key, PublicKeyStore store,
      int depth, Set<Fingerprint> seen) {
    if (trusted == null || store == null) {
      return CheckResult.OK; // Trust checking not configured.
    }
    Fingerprint fp = new Fingerprint(key.getFingerprint());
    if (seen.contains(fp)) {
      return new CheckResult("Key is trusted in a cycle");
    }
    seen.add(fp);

    Fingerprint trustedFp = trusted.get(key.getKeyID());
    if (trustedFp != null && trustedFp.equals(fp)) {
      return CheckResult.OK; // Directly trusted.
    } else if (depth >= maxTrustDepth) {
      return new CheckResult(
          "No path of depth <= " + maxTrustDepth + " to a trusted key");
    }

    List<CheckResult> signerResults = new ArrayList<>();
    @SuppressWarnings("unchecked")
    Iterator<String> userIds = key.getUserIDs();
    while (userIds.hasNext()) {
      String userId = userIds.next();
      @SuppressWarnings("unchecked")
      Iterator<PGPSignature> sigs = key.getSignaturesForID(userId);
      while (sigs.hasNext()) {
        PGPSignature sig = sigs.next();
        // TODO(dborowitz): Handle CERTIFICATION_REVOCATION.
        if (sig.getSignatureType() != PGPSignature.DEFAULT_CERTIFICATION
            && sig.getSignatureType() != PGPSignature.POSITIVE_CERTIFICATION) {
          continue; // Not a certification.
        }

        PGPPublicKey signer = getSigner(store, sig, userId, key, signerResults);
        // TODO(dborowitz): Require self certification.
        if (signer == null
            || Arrays.equals(signer.getFingerprint(), key.getFingerprint())) {
          continue;
        }
        CheckResult signerResult = checkTrustSubpacket(sig, depth);
        if (signerResult.isOk()) {
          signerResult = check(signer, store, depth + 1, false, seen);
          if (signerResult.isOk()) {
            return CheckResult.OK;
          }
        }
        signerResults.add(new CheckResult(
            "Certification by " + keyToString(signer)
            + " is valid, but key is not trusted"));
      }
    }

    List<String> problems = new ArrayList<>();
    problems.add("No path to a trusted key");
    for (CheckResult signerResult : signerResults) {
      problems.addAll(signerResult.getProblems());
    }
    return new CheckResult(problems);
  }

  private static PGPPublicKey getSigner(PublicKeyStore store, PGPSignature sig,
      String userId, PGPPublicKey key, List<CheckResult> results) {
    try {
      PGPPublicKeyRingCollection signers = store.get(sig.getKeyID());
      if (!signers.getKeyRings().hasNext()) {
        results.add(new CheckResult(
            "Key " + keyIdToString(sig.getKeyID())
            + " used for certification is not in store"));
        return null;
      }
      PGPPublicKey signer = PublicKeyStore.getSigner(signers, sig, userId, key);
      if (signer == null) {
        results.add(new CheckResult(
            "Certification by " + keyIdToString(sig.getKeyID())
            + " is not valid"));
        return null;
      }
      return signer;
    } catch (PGPException | IOException e) {
      results.add(new CheckResult(
          "Error checking certification by " + keyIdToString(sig.getKeyID())));
      return null;
    }
  }

  private CheckResult checkTrustSubpacket(PGPSignature sig, int depth) {
    SignatureSubpacket trustSub = sig.getHashedSubPackets().getSubpacket(
        SignatureSubpacketTags.TRUST_SIG);
    if (trustSub == null || trustSub.getData().length != 2) {
      return new CheckResult("Certification is missing trust information");
    }
    byte amount = trustSub.getData()[1];
    if (amount < COMPLETE_TRUST) {
      return new CheckResult("Certification does not fully trust key");
    }
    byte level = trustSub.getData()[0];
    int required = depth + 1;
    if (level < required) {
      return new CheckResult("Certification trusts to depth " + level
          + ", but depth " + required + " is required");
    }
    return CheckResult.OK;
  }
}
