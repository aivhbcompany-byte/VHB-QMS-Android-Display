(() => {
  "use strict";

  if (window.top !== window) return;

  const NATIVE_APP = "vhb_qms_counter_audio";
  const BOOT_PRIME_MS = 1800;
  const SAMPLE_DEBOUNCE_MS = 120;
  const IDLE_RESET_MS = 3000;

  let armed = false;
  let lastSentKey = "";
  let sampleTimer = 0;
  let idleTimer = 0;

  function textOf(el) {
    if (!el) return "";
    return String(el.innerText || el.textContent || "").replace(/\s+/g, " ").trim();
  }

  function asciiFold(value) {
    return String(value || "")
      .normalize("NFD")
      .replace(/[\u0300-\u036f]/g, "")
      .replace(/Đ/g, "D")
      .replace(/đ/g, "d")
      .toUpperCase();
  }

  function isVisible(el) {
    if (!el || !el.isConnected) return false;
    const style = window.getComputedStyle(el);
    if (!style || style.display === "none" || style.visibility === "hidden" || Number(style.opacity) === 0) return false;
    const rect = el.getBoundingClientRect();
    return rect.width > 2 && rect.height > 2;
  }

  function cleanTicket(value) {
    return String(value || "")
      .toUpperCase()
      .replace(/\s+/g, "")
      .replace(/[^A-Z0-9._:-]/g, "")
      .slice(0, 32);
  }

  function plausibleTicket(value) {
    if (!value || value.length > 12) return false;
    if (/^0+$/.test(value)) return false;
    return /^(?:[A-Z]{1,3}[-_.:]?)?\d{1,6}$/i.test(value);
  }

  function pageLooksLikeCounterDisplay(bodyText) {
    const path = String(location.pathname || "").toLowerCase();
    if (path.includes("/qms/display/")) return true;
    const folded = asciiFold(bodyText);
    return folded.includes("QUAY") && (folded.includes("DANG PHUC VU") || folded.includes("DANG GOI"));
  }

  function activeState(bodyText) {
    const folded = asciiFold(bodyText);
    return folded.includes("DANG PHUC VU")
      || folded.includes("DANG GOI")
      || folded.includes("DANG MOI")
      || folded.includes("MOI SO");
  }

  function findTicket() {
    const selectors = [
      "[data-qms-ticket-number]",
      "[data-ticket-number]",
      "[data-current-number]",
      ".vhb-qms-ticket-number",
      ".qms-ticket-number",
      ".qms-current-number",
      ".current-number",
      ".serving-number",
      ".ticket-number",
      "#ticket-number",
      "#ticket_number",
      "#current-number",
      "#current_number"
    ];

    for (const selector of selectors) {
      for (const el of document.querySelectorAll(selector)) {
        if (!isVisible(el)) continue;
        const attr = el.getAttribute("data-qms-ticket-number")
          || el.getAttribute("data-ticket-number")
          || el.getAttribute("data-current-number")
          || "";
        const ticket = cleanTicket(attr || textOf(el));
        if (plausibleTicket(ticket)) return { ticket, explicit: true, element: el };
      }
    }

    let best = null;
    let bestScore = -1;
    const nodes = document.querySelectorAll("h1,h2,h3,strong,b,div,span,p");
    const limit = Math.min(nodes.length, 1800);
    for (let i = 0; i < limit; i++) {
      const el = nodes[i];
      if (!isVisible(el)) continue;
      const raw = textOf(el);
      if (!raw || raw.length > 14 || raw.includes(" ")) continue;
      const ticket = cleanTicket(raw);
      if (!plausibleTicket(ticket)) continue;
      const rect = el.getBoundingClientRect();
      const font = parseFloat(window.getComputedStyle(el).fontSize || "0") || 0;
      const score = font * 10000 + Math.min(rect.width * rect.height, 1000000);
      if (score > bestScore) {
        bestScore = score;
        best = { ticket, explicit: false, element: el };
      }
    }
    return best;
  }

  function findCounter(bodyText) {
    const selectors = [
      "[data-qms-counter-name]",
      "[data-counter-name]",
      ".vhb-qms-counter-name",
      ".qms-counter-name",
      ".counter-name",
      "#counter-name",
      "#counter_name"
    ];
    for (const selector of selectors) {
      const el = document.querySelector(selector);
      if (!el || !isVisible(el)) continue;
      const value = el.getAttribute("data-qms-counter-name")
        || el.getAttribute("data-counter-name")
        || textOf(el);
      if (value && value.trim()) return value.trim().slice(0, 80);
    }

    const original = String(bodyText || "");
    const match = original.match(/QUẦY\s*(?:SỐ\s*)?([A-Z0-9._:-]{1,16})/i);
    if (match) return ("Quầy " + match[1]).slice(0, 80);

    const folded = asciiFold(original);
    const foldedMatch = folded.match(/QUAY\s*(?:SO\s*)?([A-Z0-9._:-]{1,16})/i);
    if (foldedMatch) return ("Quầy " + foldedMatch[1]).slice(0, 80);
    return "";
  }

  function findCallId(ticketElement) {
    const candidates = [];
    if (ticketElement) {
      candidates.push(ticketElement);
      const parent = ticketElement.closest("[data-qms-call-id],[data-call-id]");
      if (parent) candidates.push(parent);
    }
    const global = document.querySelector("[data-qms-call-id],[data-call-id]");
    if (global) candidates.push(global);

    for (const el of candidates) {
      const value = el.getAttribute("data-qms-call-id") || el.getAttribute("data-call-id") || "";
      const clean = String(value).trim().replace(/[^A-Za-z0-9._:-]/g, "").slice(0, 96);
      if (clean) return clean;
    }
    return "";
  }

  function detectState() {
    if (!document.body) return null;
    const bodyText = textOf(document.body);
    if (!pageLooksLikeCounterDisplay(bodyText)) return null;

    const ticketInfo = findTicket();
    if (!ticketInfo) return null;
    if (!ticketInfo.explicit && !activeState(bodyText)) return null;

    const counter = findCounter(bodyText);
    const callId = findCallId(ticketInfo.element);
    const key = callId ? `call:${callId}` : `ticket:${counter.toLowerCase()}|${ticketInfo.ticket.toLowerCase()}`;
    return { ticket: ticketInfo.ticket, counter, callId, key };
  }

  function sendState(state, initial) {
    if (!state) return;
    try {
      browser.runtime.sendNativeMessage(NATIVE_APP, {
        type: "counter_state",
        ticket: state.ticket,
        counter: state.counter,
        callId: state.callId,
        initial: Boolean(initial)
      });
    } catch (_) { }
  }

  function scheduleIdleReset() {
    if (idleTimer) return;
    idleTimer = window.setTimeout(() => {
      idleTimer = 0;
      if (!detectState()) lastSentKey = "";
    }, IDLE_RESET_MS);
  }

  function sample() {
    sampleTimer = 0;
    const state = detectState();

    if (!armed) return;
    if (!state) {
      scheduleIdleReset();
      return;
    }

    if (idleTimer) {
      clearTimeout(idleTimer);
      idleTimer = 0;
    }
    if (state.key === lastSentKey) return;
    lastSentKey = state.key;
    sendState(state, false);
  }

  function scheduleSample() {
    if (sampleTimer) clearTimeout(sampleTimer);
    sampleTimer = window.setTimeout(sample, SAMPLE_DEBOUNCE_MS);
  }

  const observer = new MutationObserver(scheduleSample);
  if (document.documentElement) {
    observer.observe(document.documentElement, {
      childList: true,
      subtree: true,
      characterData: true,
      attributes: true,
      attributeFilter: [
        "class", "style", "data-qms-ticket-number", "data-ticket-number",
        "data-current-number", "data-qms-counter-name", "data-counter-name",
        "data-qms-call-id", "data-call-id"
      ]
    });
  }

  window.setTimeout(() => {
    const state = detectState();
    armed = true;
    if (state) {
      lastSentKey = state.key;
      sendState(state, true);
    }
    scheduleSample();
  }, BOOT_PRIME_MS);
})();
