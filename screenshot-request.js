import { getApps, getApp, initializeApp } from 'https://www.gstatic.com/firebasejs/10.12.2/firebase-app.js';
import { getAuth } from 'https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js';
import { getFirestore, doc, getDoc, setDoc, collection, getDocs, query, where, limit } from 'https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore.js';

const config = {
  apiKey: 'AIzaSyB72y9YkdCifEodbo2g_43FbbcDbvg0QF4',
  authDomain: 'oilanazorat-3c8de.firebaseapp.com',
  projectId: 'oilanazorat-3c8de',
  storageBucket: 'oilanazorat-3c8de.firebasestorage.app',
  messagingSenderId: '1086556994078',
  appId: '1:1086556994078:web:5e576984e784d0bbdc4c0f',
  measurementId: 'G-NWER8TE97X'
};

const app = getApps().length ? getApp() : initializeApp(config);
const auth = getAuth(app);
const db = getFirestore(app);

let requestButton = null;
let busy = false;

function selectedFamily() {
  return localStorage.getItem('family');
}

async function selectedChild() {
  const family = selectedFamily();
  const name = document.getElementById('childName')?.textContent?.trim();
  if (!family || !name) return null;
  const q = query(collection(db, 'families', family, 'children'), where('nomi', '==', name), limit(1));
  const snap = await getDocs(q);
  if (snap.empty) return null;
  return { family, id: snap.docs[0].id };
}

function setStatus(text, ok = false) {
  if (!requestButton) return;
  requestButton.textContent = text;
  requestButton.style.background = ok ? '#ecfdf5' : '#111827';
  requestButton.style.color = ok ? '#047857' : '#fff';
}

async function requestScreenshot() {
  if (busy) return;
  const user = auth.currentUser;
  if (!user) return alert('Avval ota-ona paneliga kiring.');

  busy = true;
  setStatus('⏳ So‘rov yuborilmoqda…');
  try {
    const parent = await getDoc(doc(db, 'parents', user.uid));
    if (!parent.exists() || parent.data()?.premium !== true) {
      alert('Screenshot olish Premium funksiyasidir.');
      setStatus('📸 Screenshot olish');
      busy = false;
      return;
    }

    const target = await selectedChild();
    if (!target) throw new Error('Farzand qurilmasi aniqlanmadi.');

    const requestId = `${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
    const ref = doc(db, 'families', target.family, 'children', target.id, 'screenshot_requests', 'current');
    await setDoc(ref, {
      requestId,
      status: 'requested',
      requestedByUid: user.uid,
      requestedAt: Date.now(),
      updatedAt: Date.now()
    });

    setStatus('⏳ Farzand qurilmasidan kutilmoqda…');
    const started = Date.now();
    const timer = setInterval(async () => {
      try {
        const snap = await getDoc(ref);
        const status = snap.data()?.status;
        if (status === 'completed') {
          clearInterval(timer);
          setStatus('✅ Screenshot tayyor', true);
          busy = false;
          document.querySelector('[data-tab="ss"]')?.click();
          setTimeout(() => setStatus('📸 Screenshot olish'), 2500);
        } else if (status === 'failed' || Date.now() - started > 45000) {
          clearInterval(timer);
          setStatus('❌ Screenshot olinmadi');
          busy = false;
          setTimeout(() => setStatus('📸 Screenshot olish'), 2500);
        }
      } catch (e) {
        console.error('screenshot request poll', e);
      }
    }, 1500);
  } catch (e) {
    console.error('screenshot request', e);
    alert(e.message || 'Screenshot so‘rovini yuborib bo‘lmadi.');
    setStatus('📸 Screenshot olish');
    busy = false;
  }
}

function installButton() {
  const tab = document.querySelector('[data-tab="ss"]');
  const content = document.getElementById('content');
  if (!tab || !content) return;
  if (!tab.classList.contains('active')) {
    requestButton?.remove();
    requestButton = null;
    return;
  }
  if (requestButton?.isConnected) return;

  requestButton = document.createElement('button');
  requestButton.type = 'button';
  requestButton.textContent = '📸 Screenshot olish';
  requestButton.style.cssText = 'width:100%;border:0;border-radius:14px;background:#111827;color:#fff;padding:13px 15px;margin:0 0 12px;font-size:13px;font-weight:850;cursor:pointer;box-shadow:0 6px 18px #10182818';
  requestButton.onclick = requestScreenshot;
  content.parentNode.insertBefore(requestButton, content);
}

const observer = new MutationObserver(installButton);
observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
setInterval(installButton, 700);
setTimeout(installButton, 500);
