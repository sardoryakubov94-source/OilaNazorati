const { onRequest } = require("firebase-functions/v2/https");
const { defineSecret, defineString } = require("firebase-functions/params");
const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const { RtcTokenBuilder, RtcRole } = require("agora-token");

initializeApp();

const AGORA_APP_ID = defineString("AGORA_APP_ID");
const AGORA_APP_CERTIFICATE = defineSecret("AGORA_APP_CERTIFICATE");

exports.agoraVoiceToken = onRequest(
  { region: "us-central1", secrets: [AGORA_APP_CERTIFICATE], maxInstances: 10 },
  async (req, res) => {
    if (req.method !== "POST") {
      return res.status(405).json({ error: "method_not_allowed" });
    }

    try {
      const authHeader = req.get("Authorization") || "";
      const match = authHeader.match(/^Bearer (.+)$/);
      if (!match) return res.status(401).json({ error: "unauthorized" });

      const decoded = await getAuth().verifyIdToken(match[1]);
      const body = req.body || {};
      const { familyCode, childUid, requestId, channelName, uid, role } = body;

      if (
        typeof familyCode !== "string" || !/^[A-Za-z0-9_-]{4,80}$/.test(familyCode) ||
        typeof childUid !== "string" || childUid.length < 5 ||
        typeof requestId !== "string" || requestId.length < 8 ||
        typeof channelName !== "string" || !/^[A-Za-z0-9_]{4,64}$/.test(channelName) ||
        !Number.isInteger(uid) || uid < 1 || uid > 2147483647 ||
        !["publisher", "subscriber"].includes(role)
      ) {
        return res.status(400).json({ error: "invalid_request" });
      }

      const db = getFirestore();
      const familyRef = db.collection("families").doc(familyCode);
      const [family, child, request, micSettings] = await Promise.all([
        familyRef.get(),
        familyRef.collection("children").doc(childUid).get(),
        familyRef.collection("children").doc(childUid).collection("mic_requests").doc("current").get(),
        familyRef.collection("children").doc(childUid).collection("mic_settings").doc("current").get()
      ]);

      if (!family.exists || !child.exists || !request.exists || !micSettings.exists) {
        return res.status(404).json({ error: "voice_session_not_found" });
      }

      const familyData = family.data() || {};
      const requestData = request.data() || {};
      const consentData = micSettings.data() || {};
      if (consentData.remoteAudioConsent !== true || consentData.consentVersion !== 1) {
        return res.status(403).json({ error: "child_consent_required" });
      }
      if (requestData.status === "requested" &&
          Number(requestData.expiresAtMs || 0) < Date.now()) {
        return res.status(410).json({ error: "voice_request_expired" });
      }
      if (
        requestData.requestedByUid !== familyData.ownerUid ||
        requestData.requestId !== requestId ||
        requestData.channelName !== channelName ||
        !["requested", "active"].includes(requestData.status)
      ) {
        return res.status(403).json({ error: "voice_session_not_active" });
      }

      const isOwner = familyData.ownerUid === decoded.uid;
      const isChild = decoded.uid === childUid;
      const parent = await db.collection("parents").doc(familyData.ownerUid).get();
      const premium = parent.exists && parent.data().premium === true;
      if (!premium) return res.status(403).json({ error: "premium_required" });
      if (role === "publisher" && !isChild) {
        return res.status(403).json({ error: "child_publisher_only" });
      }
      if (role === "subscriber") {
        if (decoded.firebase?.sign_in_provider === "anonymous" || !isOwner) return res.status(403).json({ error: "family_owner_only" });

      }

      const appId = AGORA_APP_ID.value();
      const certificate = AGORA_APP_CERTIFICATE.value();
      if (!appId || !certificate) {
        console.error("Agora credentials are not configured");
        return res.status(503).json({ error: "voice_service_not_configured" });
      }

      const now = Math.floor(Date.now() / 1000);
      const expiresAt = now + 3600;
      const tokenRole = role === "publisher" ? RtcRole.PUBLISHER : RtcRole.SUBSCRIBER;
      const token = RtcTokenBuilder.buildTokenWithUid(
        appId, certificate, channelName, uid, tokenRole, expiresAt, expiresAt
      );

      return res.status(200).json({ token, appId, expiresAt });
    } catch (error) {
      console.error("agoraVoiceToken failed", error);
      return res.status(500).json({ error: "token_generation_failed" });
    }
  }
);
