/**
 * Flow Import/Export — Keycloak Admin Console Extension
 *
 * Places "Import Flow" and "Export Flow" buttons as a fixed floating panel
 * at the top-right corner of the Authentication page, below the KC masthead.
 *
 * REST API endpoints (provided by FlowUploadProvider SPI):
 *   POST /realms/{realm}/flow-uploader               — import flow JSON
 *   GET  /realms/{realm}/flow-uploader/export/{alias} — export single flow
 *   GET  /realms/{realm}/flow-uploader/list            — list custom flows
 *   GET  /realms/{realm}/flow-uploader/export-all      — export all custom flows
 */

(function () {
  "use strict";

  // ─── Constants ────────────────────────────────────────────────────────
  const PROVIDER_ID     = "flow-uploader";
  const PANEL_ID        = "flow-mgmt-panel";
  const MODAL_ID        = "flow-mgmt-modal";
  const NOTIF_ID        = "flow-mgmt-notif";
  const ANIM_STYLE_ID   = "flow-mgmt-anim";
  const CHECK_MS        = 800;

  // ─── State ────────────────────────────────────────────────────────────
  let currentRealm = null;
  let baseUrl      = null;
  let lastPath     = "";

  // ─── Boot ─────────────────────────────────────────────────────────────
  function init() {
    const envEl = document.getElementById("environment");
    if (envEl) {
      try {
        const env = JSON.parse(envEl.textContent);
        baseUrl = (env.authServerUrl || env.serverBaseUrl || "").replace(/\/$/, "");
      } catch (_) {}
    }

    injectAnimations();
    startWatcher();
  }

  // ─── Route watcher ────────────────────────────────────────────────────
  function startWatcher() {
    setInterval(checkRoute, CHECK_MS);

    // Also react to DOM mutations (React re-renders)
    new MutationObserver(checkRoute).observe(document.body, {
      childList: true,
      subtree: true,
    });
  }

  function checkRoute() {
    const path = window.location.hash || window.location.pathname;
    if (path === lastPath) return;
    lastPath = path;

    // Match: /admin/{realm}/authentication  OR  /{realm}/authentication
    const m = path.match(/(?:\/admin\/|#\/)([^/]+)\/authentication(?:\/|$|#)/);
    if (m) {
      currentRealm = m[1];
      // Small delay to let React paint the page first
      setTimeout(injectPanel, 600);
    } else {
      removePanel();
    }
  }

  // ─── Panel injection ──────────────────────────────────────────────────
  function injectPanel() {
    if (document.getElementById(PANEL_ID)) return; // already present

    // Use a fixed offset that clears the KC masthead (76px) plus any
    // sub-toolbar / breadcrumb bar / dropdown overhang comfortably.
    const topOffset = 110;

    const panel = document.createElement("div");
    panel.id    = PANEL_ID;
    panel.setAttribute("aria-label", "Flow management actions");
    panel.style.cssText = [
      "position: fixed",
      `top: ${topOffset}px`,
      "right: 20px",
      // Stay above page content but strictly BELOW masthead (PF5 masthead z-index is 200)
      "z-index: 199",
      "display: flex",
      "gap: 8px",
      "align-items: center",
      "animation: flowMgmtFadeIn 0.25s ease-out",
    ].join(";");

    const importBtn = makeButton("Import flow", handleImport);
    importBtn.id    = "flow-import-btn";
    importBtn.title = "Import an authentication flow from a JSON file";

    const exportBtn = makeButton("Export flow", handleExport);
    exportBtn.id    = "flow-export-btn";
    exportBtn.title = "Export one or more authentication flows to JSON";

    panel.appendChild(importBtn);
    panel.appendChild(exportBtn);
    document.body.appendChild(panel);
  }

  function removePanel() {
    const el = document.getElementById(PANEL_ID);
    if (el) el.remove();
  }

  function makeButton(text, onClick) {
    const btn = document.createElement("button");
    btn.type      = "button";
    btn.className = "pf-v5-c-button pf-m-secondary";
    btn.textContent = text;
    btn.addEventListener("click", onClick);
    return btn;
  }

  // ─── Import handler ───────────────────────────────────────────────────
  function handleImport() {
    const input   = document.createElement("input");
    input.type    = "file";
    input.accept  = ".json";
    input.style.display = "none";

    input.addEventListener("change", async (e) => {
      const file = e.target.files[0];
      if (!file) return;
      input.remove();

      try {
        const text = await file.text();
        const json = JSON.parse(text);
        notify("info", `Importing flows from "${file.name}"…`);

        const res = await apiCall("", { method: "POST", body: JSON.stringify(json) });
        const names = res.flows ? res.flows.join(", ") : "unknown";
        notify("success", `✓ Imported: ${names}`);
        setTimeout(() => window.location.reload(), 1800);
      } catch (err) {
        notify("danger", `Import failed: ${err.message}`);
      }
    });

    document.body.appendChild(input);
    input.click();
  }

  // ─── Export handler ───────────────────────────────────────────────────
  async function handleExport() {
    try {
      notify("info", "Loading flows…");
      const flows = await apiCall("/list");
      if (!flows || flows.length === 0) {
        notify("warning", "No custom (non-built-in) flows found in this realm.");
        return;
      }
      showExportModal(flows);
    } catch (err) {
      notify("danger", `Could not load flows: ${err.message}`);
    }
  }

  // ─── Export modal ─────────────────────────────────────────────────────
  function showExportModal(flows) {
    document.getElementById(MODAL_ID)?.remove();

    /* ── backdrop ── */
    const backdrop = document.createElement("div");
    backdrop.id    = MODAL_ID;
    backdrop.style.cssText = [
      "position:fixed",
      "inset:0",
      "background:rgba(0,0,0,0.5)",
      "z-index:9999",
      "display:flex",
      "align-items:center",
      "justify-content:center",
      "animation:flowMgmtFadeIn 0.2s ease-out",
    ].join(";");

    /* ── dialog ── */
    const dialog = document.createElement("div");
    dialog.role  = "dialog";
    dialog.setAttribute("aria-modal", "true");
    dialog.setAttribute("aria-label", "Export authentication flows");
    dialog.style.cssText = [
      "background:var(--pf-v5-global--BackgroundColor--100,#fff)",
      "color:var(--pf-v5-global--Color--100,#151515)",
      "border-radius:10px",
      "padding:28px",
      "width:480px",
      "max-width:90vw",
      "max-height:80vh",
      "overflow-y:auto",
      "box-shadow:0 8px 32px rgba(0,0,0,0.22)",
      "display:flex",
      "flex-direction:column",
      "gap:16px",
    ].join(";");

    /* title */
    const title = document.createElement("h2");
    title.style.cssText = "margin:0;font-size:18px;font-weight:700;";
    title.textContent   = "Export Authentication Flows";

    /* description */
    const desc = document.createElement("p");
    desc.style.cssText  = "margin:0;font-size:13px;color:var(--pf-v5-global--Color--200,#6a6e73);";
    desc.textContent    = "Select one or more flows to download as JSON.";

    /* select-all */
    const selectAllWrap  = document.createElement("label");
    selectAllWrap.style.cssText = [
      "display:flex",
      "align-items:center",
      "gap:8px",
      "font-weight:600",
      "font-size:13px",
      "cursor:pointer",
      "padding-bottom:8px",
      "border-bottom:1px solid var(--pf-v5-global--BorderColor--100,#d2d2d2)",
    ].join(";");
    const selectAllCb = document.createElement("input");
    selectAllCb.type  = "checkbox";
    selectAllCb.id    = "flow-select-all";
    selectAllWrap.appendChild(selectAllCb);
    selectAllWrap.appendChild(document.createTextNode("Select all"));

    /* flow list */
    const list = document.createElement("div");
    list.style.cssText = [
      "display:flex",
      "flex-direction:column",
      "gap:4px",
      "max-height:280px",
      "overflow-y:auto",
    ].join(";");

    const checkboxes = [];
    flows.forEach((flow, i) => {
      const row   = document.createElement("label");
      row.htmlFor = `flow-cb-${i}`;
      row.style.cssText = [
        "display:flex",
        "align-items:flex-start",
        "gap:10px",
        "padding:10px 12px",
        "border-radius:6px",
        "cursor:pointer",
        "border:1px solid var(--pf-v5-global--BorderColor--100,#d2d2d2)",
        "transition:background 0.12s",
      ].join(";");

      row.addEventListener("mouseenter", () => row.style.background = "var(--pf-v5-global--BackgroundColor--200,#f0f0f0)");
      row.addEventListener("mouseleave", () => row.style.background = "");

      const cb   = document.createElement("input");
      cb.type    = "checkbox";
      cb.id      = `flow-cb-${i}`;
      cb.value   = flow.alias;
      cb.style.marginTop = "2px";
      checkboxes.push(cb);

      const info = document.createElement("div");
      info.innerHTML = `<strong>${esc(flow.alias)}</strong><br>
        <span style="font-size:12px;color:var(--pf-v5-global--Color--200,#6a6e73)">
          ${esc(flow.description || "No description")}
        </span>`;

      row.appendChild(cb);
      row.appendChild(info);
      list.appendChild(row);
    });

    selectAllCb.addEventListener("change", () =>
      checkboxes.forEach(cb => (cb.checked = selectAllCb.checked))
    );

    /* action buttons */
    const actions = document.createElement("div");
    actions.style.cssText = "display:flex;gap:10px;justify-content:flex-end;padding-top:4px;";

    const cancelBtn = document.createElement("button");
    cancelBtn.type  = "button";
    cancelBtn.textContent = "Cancel";
    cancelBtn.style.cssText = [
      "padding:8px 18px",
      "border-radius:6px",
      "border:1px solid var(--pf-v5-global--BorderColor--100,#d2d2d2)",
      "background:transparent",
      "cursor:pointer",
      "font-size:13px",
      "font-weight:600",
    ].join(";");
    cancelBtn.addEventListener("click", () => backdrop.remove());

    const exportBtn = document.createElement("button");
    exportBtn.type  = "button";
    exportBtn.textContent = "Export Selected";
    exportBtn.style.cssText = [
      "padding:8px 18px",
      "border-radius:6px",
      "border:none",
      "background:#0066cc",
      "color:#fff",
      "cursor:pointer",
      "font-size:13px",
      "font-weight:600",
      "transition:background 0.15s",
    ].join(";");
    exportBtn.addEventListener("mouseenter", () => exportBtn.style.background = "#004d99");
    exportBtn.addEventListener("mouseleave", () => exportBtn.style.background = "#0066cc");

    exportBtn.addEventListener("click", async () => {
      const selected = checkboxes.filter(cb => cb.checked).map(cb => cb.value);
      if (selected.length === 0) {
        notify("warning", "Please select at least one flow.");
        return;
      }

      try {
        exportBtn.disabled     = true;
        exportBtn.textContent  = "Exporting…";

        let data, filename;
        if (selected.length === flows.length) {
          data     = await apiCall("/export-all");
          filename = `${currentRealm}-all-flows-export.json`;
        } else if (selected.length === 1) {
          data     = await apiCall(`/export/${encodeURIComponent(selected[0])}`);
          filename = `${selected[0]}-export.json`;
        } else {
          data = [];
          for (const alias of selected) {
            data.push(await apiCall(`/export/${encodeURIComponent(alias)}`));
          }
          filename = `${currentRealm}-flows-export.json`;
        }

        downloadJson(data, filename);
        notify("success", `✓ Exported ${selected.length} flow(s) → ${filename}`);
        backdrop.remove();
      } catch (err) {
        notify("danger", `Export failed: ${err.message}`);
        exportBtn.disabled    = false;
        exportBtn.textContent = "Export Selected";
      }
    });

    actions.appendChild(cancelBtn);
    actions.appendChild(exportBtn);

    dialog.appendChild(title);
    dialog.appendChild(desc);
    dialog.appendChild(selectAllWrap);
    dialog.appendChild(list);
    dialog.appendChild(actions);

    backdrop.appendChild(dialog);
    backdrop.addEventListener("click", e => { if (e.target === backdrop) backdrop.remove(); });
    document.body.appendChild(backdrop);
  }

  // ─── API helpers ──────────────────────────────────────────────────────
  async function apiCall(path, opts = {}) {
    const token = await getToken();
    const url   = `${baseUrl}/realms/${currentRealm}/${PROVIDER_ID}${path}`;
    const res   = await fetch(url, {
      ...opts,
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...opts.headers,
      },
    });
    if (!res.ok) {
      const body = await res.text().catch(() => "");
      throw new Error(`HTTP ${res.status}: ${body}`);
    }
    return res.json();
  }

  async function getToken() {
    if (window.keycloak?.token) {
      if (window.keycloak.isTokenExpired?.(30)) {
        await window.keycloak.updateToken(30).catch(() => {});
      }
      return window.keycloak.token;
    }
    return sessionStorage.getItem("kc-token") || localStorage.getItem("kc-token") || null;
  }

  // ─── Notifications ────────────────────────────────────────────────────
  const COLOR = {
    success: { bg:"#e7f1e4", border:"#3e8635" },
    danger:  { bg:"#fde3e3", border:"#c9190b" },
    warning: { bg:"#fdf7e7", border:"#f0ab00" },
    info:    { bg:"#e7f1fa", border:"#0066cc" },
  };

  function notify(type, msg) {
    document.getElementById(NOTIF_ID)?.remove();
    const c   = COLOR[type] || COLOR.info;
    const el  = document.createElement("div");
    el.id     = NOTIF_ID;
    el.style.cssText = [
      "position:fixed",
      "top:16px",
      "right:16px",
      "z-index:20000",
      "padding:12px 16px",
      "border-radius:6px",
      `background:${c.bg}`,
      `border-left:4px solid ${c.border}`,
      "box-shadow:0 2px 12px rgba(0,0,0,0.15)",
      "font-size:13px",
      "max-width:420px",
      "display:flex",
      "align-items:center",
      "gap:10px",
      "animation:flowMgmtSlideIn 0.3s ease-out",
    ].join(";");

    el.innerHTML = `
      <span style="flex:1;color:#151515">${esc(msg)}</span>
      <button onclick="this.parentElement.remove()" style="
        background:none;border:none;cursor:pointer;font-size:18px;
        color:#6a6e73;padding:0 2px;line-height:1;
      ">×</button>`;

    document.body.appendChild(el);
    setTimeout(() => {
      if (el.parentElement) {
        el.style.animation = "flowMgmtSlideOut 0.3s ease-in";
        setTimeout(() => el.remove(), 300);
      }
    }, 5000);
  }

  // ─── Utilities ────────────────────────────────────────────────────────
  function downloadJson(data, filename) {
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
    const url  = URL.createObjectURL(blob);
    const a    = Object.assign(document.createElement("a"), { href: url, download: filename });
    a.click();
    URL.revokeObjectURL(url);
  }

  function esc(str) {
    const d = document.createElement("div");
    d.textContent = String(str ?? "");
    return d.innerHTML;
  }

  function injectAnimations() {
    if (document.getElementById(ANIM_STYLE_ID)) return;
    const s = document.createElement("style");
    s.id    = ANIM_STYLE_ID;
    s.textContent = `
      @keyframes flowMgmtFadeIn {
        from { opacity:0; transform:translateY(-6px); }
        to   { opacity:1; transform:translateY(0); }
      }
      @keyframes flowMgmtSlideIn {
        from { opacity:0; transform:translateX(60px); }
        to   { opacity:1; transform:translateX(0); }
      }
      @keyframes flowMgmtSlideOut {
        from { opacity:1; transform:translateX(0); }
        to   { opacity:0; transform:translateX(60px); }
      }
      #${PANEL_ID} button:focus-visible {
        outline: 3px solid #0066cc;
        outline-offset: 2px;
      }
    `;
    document.head.appendChild(s);
  }

  // ─── Bootstrap ────────────────────────────────────────────────────────
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
