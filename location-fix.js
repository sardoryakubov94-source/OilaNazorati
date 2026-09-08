// Location list -> selected point on a dedicated Leaflet map.
(function () {
  let selectedMap = null;
  let selectedMarker = null;

  function parseRow(row) {
    const link = row.querySelector('a.dirLink');
    if (!link) return null;
    const m = link.href.match(/[?&]q=([-0-9.]+),([-0-9.]+)/);
    if (!m) return null;
    return { lat: Number(m[1]), lng: Number(m[2]) };
  }

  function ensureSelectedMap() {
    const list = document.getElementById('locList');
    if (!list) return null;
    let box = document.getElementById('selectedLocationMapWrap');
    if (box) return box;
    box = document.createElement('div');
    box.id = 'selectedLocationMapWrap';
    box.style.cssText = 'display:none;margin:0 0 12px;border:1px solid #e1e6ee;border-radius:16px;overflow:hidden;background:#fff;box-shadow:0 6px 18px #1018280a';
    box.innerHTML = '<div style="padding:12px 14px;font-size:13px;font-weight:850">📍 Tanlangan joy</div><div id="selectedLocationMap" style="height:220px;width:100%"></div>';
    list.parentNode.insertBefore(box, list);
    return box;
  }

  function showSelected(point) {
    if (!window.L || !point) return;
    const box = ensureSelectedMap();
    const mapEl = document.getElementById('selectedLocationMap');
    if (!box || !mapEl) return;
    box.style.display = 'block';

    if (selectedMap) {
      selectedMap.remove();
      selectedMap = null;
      selectedMarker = null;
    }

    selectedMap = L.map(mapEl, { zoomControl: true }).setView([point.lat, point.lng], 17);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '© OpenStreetMap', maxZoom: 19
    }).addTo(selectedMap);
    selectedMarker = L.circleMarker([point.lat, point.lng], {
      radius: 10, color: '#fff', weight: 3, fillColor: '#dc2626', fillOpacity: 1
    }).addTo(selectedMap);
    selectedMarker.bindPopup('<b>Tanlangan joy</b><br>' + point.lat.toFixed(5) + '°, ' + point.lng.toFixed(5) + '°').openPopup();
    setTimeout(() => selectedMap && selectedMap.invalidateSize(), 80);
    box.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }

  function bindRows() {
    const rows = document.querySelectorAll('#locList .row');
    rows.forEach(row => {
      if (row.dataset.locationBound === '1') return;
      const point = parseRow(row);
      if (!point) return;
      row.dataset.locationBound = '1';
      row.style.cursor = 'pointer';
      row.title = 'Xaritada ko‘rish';
      row.addEventListener('click', function (event) {
        if (event.target.closest('a')) return;
        showSelected(point);
      });
    });
  }

  const observer = new MutationObserver(bindRows);
  observer.observe(document.body, { childList: true, subtree: true });
  setInterval(bindRows, 800);
  setTimeout(bindRows, 500);
})();
