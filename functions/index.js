const { onRequest } = require("firebase-functions/v2/https");
const { defineSecret, defineString } = require("firebase-functions/params");
const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
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
      const { familyCode, childUid, requestId, channelName, uid, role, purpose = "voice" } = body;
      const isScreen = purpose === "screen";
      const requestCollection = isScreen ? "screen_requests" : "mic_requests";

      if (
        typeof familyCode !== "string" || !/^[A-Za-z0-9_-]{4,80}$/.test(familyCode) ||
        typeof childUid !== "string" || childUid.length < 5 ||
        typeof requestId !== "string" || requestId.length < 8 ||
        typeof channelName !== "string" || !/^[A-Za-z0-9_]{4,64}$/.test(channelName) ||
        !Number.isInteger(uid) || uid < 0 || uid > 4294967295 ||
        (uid === 0 && role !== "screen_publisher") ||
        !(isScreen ? ["screen_publisher", "screen_subscriber"].includes(role) : ["publisher", "subscriber"].includes(role))
      ) {
        return res.status(400).json({ error: "invalid_request" });
      }

      const db = getFirestore();
      const familyRef = db.collection("families").doc(familyCode);
      const [family, child, request] = await Promise.all([
        familyRef.get(),
        familyRef.collection("children").doc(childUid).get(),
        familyRef.collection("children").doc(childUid).collection(requestCollection).doc("current").get()
      ]);

      if (!family.exists || !child.exists || !request.exists) {
        return res.status(404).json({ error: "session_not_found" });
      }

      const familyData = family.data() || {};
      const childData = child.data() || {};
      const childPlatform = childData.childPlatform || childData.platform || "unknown";
      const requestData = request.data() || {};
      if (!isScreen && childPlatform === "ios") {
        const micSettings = await familyRef.collection("children").doc(childUid)
          .collection("mic_settings").doc("current").get();
        const consentData = micSettings.data() || {};
        if (!micSettings.exists || consentData.remoteAudioConsent !== true || consentData.consentVersion !== 1) {
          return res.status(403).json({ error: "child_consent_required" });
        }
      }
      const requestExpiresAt = Number(requestData.expiresAtMs || 0);
      if (requestData.status === "requested" &&
          requestExpiresAt > 0 && requestExpiresAt < Date.now()) {
        return res.status(410).json({ error: "request_expired" });
      }
      if (
        requestData.requestedByUid !== familyData.ownerUid ||
        requestData.requestId !== requestId ||
        requestData.channelName !== channelName ||
        !["requested", "active"].includes(requestData.status)
      ) {
        return res.status(403).json({ error: "session_not_active" });
      }

      const isOwner = familyData.ownerUid === decoded.uid;
      const isChild = decoded.uid === childUid;
      const parent = await db.collection("parents").doc(familyData.ownerUid).get();
      const premium = parent.exists && parent.data().premium === true;
      if (!premium) return res.status(403).json({ error: "premium_required" });
      if ((role === "publisher" || role === "screen_publisher") && (!isChild || childPlatform !== "ios")) {
        return res.status(403).json({ error: "child_publisher_only" });
      }
      if (role === "subscriber" || role === "screen_subscriber") {
        if (decoded.firebase?.sign_in_provider === "anonymous" || !isOwner) {
          return res.status(403).json({ error: "family_owner_only" });
        }
        if (requestData.status !== "active") {
          return res.status(409).json({ error: "child_session_not_active" });
        }
      }

      const appId = AGORA_APP_ID.value();
      const certificate = AGORA_APP_CERTIFICATE.value();
      if (!appId || !certificate) {
        console.error("Agora credentials are not configured");
        return res.status(503).json({ error: "rtc_service_not_configured" });
      }

      const now = Math.floor(Date.now() / 1000);
      const tokenLifetimeSeconds = 3600;
      const expiresAt = now + tokenLifetimeSeconds;
      const tokenRole = ["publisher", "screen_publisher"].includes(role) ? RtcRole.PUBLISHER : RtcRole.SUBSCRIBER;
      const token = RtcTokenBuilder.buildTokenWithUid(
        appId, certificate, channelName, uid, tokenRole,
        tokenLifetimeSeconds, tokenLifetimeSeconds
      );

      return res.status(200).json({ token, appId, expiresAt });
    } catch (error) {
      console.error("agoraVoiceToken failed", error);
      return res.status(500).json({ error: "token_generation_failed" });
    }
  }
);


// SOS schedules are checked by a trusted server process. This continues even
// when the child's phone is offline or powered off, provided the schedule was
// synced to Firestore before the device went offline.
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { onDocumentCreated } = require("firebase-functions/v2/firestore");

exports.processScheduledSos = onSchedule(
  { schedule: "every 1 minutes", region: "us-central1", timeZone: "Asia/Tashkent", maxInstances: 1 },
  async () => {
    const db = getFirestore();
    const now = Date.now();
    const due = await db.collectionGroup("sos_schedules")
      .where("status", "==", "scheduled")
      .where("serverNotifyAtMs", "<=", now)
      .limit(100)
      .get();

    for (const scheduleDoc of due.docs) {
      const data = scheduleDoc.data() || {};
      const childRef = scheduleDoc.ref.parent.parent;
      if (!childRef) continue;
      const familyRef = childRef.parent.parent;
      if (!familyRef) continue;
      const alertRef = childRef.collection("sos_alerts").doc(scheduleDoc.id);
      await db.runTransaction(async tx => {
        const fresh = await tx.get(scheduleDoc.ref);
        if (!fresh.exists || fresh.data()?.status !== "scheduled" ||
            Number(fresh.data()?.serverNotifyAtMs || 0) > Date.now()) return;
        tx.update(scheduleDoc.ref, { status: "server_notified", serverNotifiedAtMs: Date.now() });
        tx.set(alertRef, {
          scheduleId: scheduleDoc.id,
          childId: childRef.id,
          childName: data.childName || "Farzand",
          message: "SOS taymeri tugadi. Bola 1 daqiqa ichida javob bermadi yoki qurilma bilan aloqa yo'q.",
          createdAtMs: Date.now(),
          status: "active",
          source: "server_timer"
        }, { merge: true });
      });
    }
  }
);

// Any SOS alert (manual or timed, local or server-created) notifies the family
// owner. The Firestore alert remains the durable source for the parent dashboard.
exports.notifyParentOnSosAlert = onDocumentCreated(
  { document: "families/{familyCode}/children/{childId}/sos_alerts/{alertId}", region: "us-central1", maxInstances: 10 },
  async event => {
    const data = event.data?.data() || {};
    const db = getFirestore();
    const family = await db.collection("families").doc(event.params.familyCode).get();
    const ownerUid = family.data()?.ownerUid;
    if (!ownerUid) return;
    const parentRef = db.collection("parents").doc(ownerUid);
    const parent = await parentRef.get();
    const tokens = (parent.data()?.fcmTokens || []).filter(t => typeof t === "string" && t.length > 20);
    if (!tokens.length) return;

    const response = await getMessaging().sendEachForMulticast({
      tokens,
      notification: {
        title: "🆘 SOS — yordam so'rovi",
        body: data.message || ((data.childName || "Farzandingiz") + " SOS xabarini yubordi. Tekshiring.")
      },
      data: {
        type: "sos_alert",
        familyCode: event.params.familyCode,
        childId: event.params.childId,
        alertId: event.params.alertId
      },
      android: { priority: "high" }
    });
    const invalid = [];
    response.responses.forEach((r, i) => {
      if (!r.success && ["messaging/registration-token-not-registered", "messaging/invalid-registration-token"].includes(r.error?.code)) invalid.push(tokens[i]);
    });
    if (invalid.length) {
      await parentRef.update({ fcmTokens: require("firebase-admin/firestore").FieldValue.arrayRemove(...invalid) });
    }
  }
);
