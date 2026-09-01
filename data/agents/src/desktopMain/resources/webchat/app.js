(() => {
  const SESSION_KEY = "andy.networkAccessSession";
  const LEGACY_TOKEN_KEY = "andy.networkAccessToken";
  const $ = (id) => document.getElementById(id);

  const views = {
    auth: $("view-auth"),
    list: $("view-list"),
    chat: $("view-chat"),
    new: $("view-new"),
  };

  let token = localStorage.getItem(SESSION_KEY) || "";
  let routePromise = null;

  function loginParamsFromUrl() {
    const hash = location.hash.replace(/^#/, "") || "/";
    const params = new URLSearchParams(location.search);
    const hashParams = new URLSearchParams(hash.split("?")[1] || "");
    return {
      hash,
      fromCode: params.get("code") || hashParams.get("code"),
      legacyToken: params.get("token") || hashParams.get("token"),
    };
  }

  function authHeaders(extra = {}) {
    return Object.assign({ Authorization: `Bearer ${token}` }, extra);
  }

  async function api(path, options = {}) {
    const res = await fetch(path, {
      cache: "no-store",
      ...options,
      headers: authHeaders(options.headers || {}),
    });
    let body = null;
    try {
      body = await res.json();
    } catch (_) {
      body = null;
    }
    if (res.status === 401) {
      const { fromCode, legacyToken } = loginParamsFromUrl();
      if (fromCode || legacyToken) {
        token = "";
        localStorage.removeItem(SESSION_KEY);
        await routeFromHash();
        throw new Error("unauthorized");
      }
      forgetToken("Session expired — sign in again.").catch(() => {});
      throw new Error("unauthorized");
    }
    if (!res.ok) {
      const err = new Error(
        (body && typeof body.error === "string" && body.error) ||
          `Request failed (${res.status})`,
      );
      err.status = res.status;
      err.body = body;
      throw err;
    }
    return body;
  }
  let currentChatId = null;
  let socket = null;
  let events = [];
  let chatMeta = null;
  let pendingInput = null;
  let projectsById = {};
  /** Project keys the user has expanded. Empty = all collapsed (matches andy tui). */
  const expandedProjects = new Set();
  let slashCommands = [];
  let slashActiveIndex = 0;
  let slashInput = null;
  let slashMenuEl = null;
  let newChatRequiresModel = false;
  let newChatDefaultRuntime = null;
  let chatWorking = false;
  let chatLoading = false;
  let optimisticUserText = null;
  let socketPreferQueryAuth = false;
  let socketConnecting = false;

  const isAndroid = /Android/i.test(navigator.userAgent);

  function show(name) {
    Object.entries(views).forEach(([key, el]) => {
      el.classList.toggle("hidden", key !== name);
    });
    syncSafeAreaTop();
  }

  const isIOS =
    /iPad|iPhone|iPod/.test(navigator.userAgent) ||
    (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  if (isIOS) {
    document.documentElement.classList.add("ios");
  }
  if (isAndroid) {
    document.documentElement.classList.add("android");
    socketPreferQueryAuth = true;
  }

  function readSafeAreaInsetTop() {
    const probe = document.createElement("div");
    probe.style.cssText =
      "position:fixed;top:0;left:0;height:0;padding-top:env(safe-area-inset-top);visibility:hidden;pointer-events:none;";
    document.documentElement.appendChild(probe);
    const top = probe.getBoundingClientRect().height;
    probe.remove();
    return top;
  }

  /** iOS standalone often reports env(safe-area-inset-top) as 0 on first paint. */
  function syncSafeAreaTop() {
    if (!isIOS) return;
    let top = readSafeAreaInsetTop();
    if (top <= 0) {
      const probe = document.createElement("div");
      probe.style.paddingTop = "env(safe-area-inset-top)";
      document.body.appendChild(probe);
      top = parseFloat(getComputedStyle(probe).paddingTop) || 0;
      document.body.removeChild(probe);
    }
    if (top <= 0 && window.visualViewport?.offsetTop > 0) {
      top = window.visualViewport.offsetTop;
    }
    if (top <= 0) {
      const anchor =
        document.querySelector("#view-list:not(.hidden) .list-header") ||
        document.querySelector("#view-chat:not(.hidden) .topbar") ||
        document.querySelector("#view-new:not(.hidden) .topbar");
      if (anchor && anchor.getBoundingClientRect().top < 8) {
        const longSide = Math.max(window.screen.width, window.screen.height);
        top = longSide >= 812 ? 47 : 20;
      }
    }
    document.documentElement.style.setProperty("--safe-top", `${Math.round(top)}px`);
  }

  /** Shrink the shell when the software keyboard is open (Android + most Chromium). */
  function syncViewportHeight() {
    if (isIOS) return;
    const vv = window.visualViewport;
    const height = Math.max(Math.round(vv ? vv.height : window.innerHeight), 200);
    document.documentElement.style.setProperty("--app-height", `${height}px`);
    if (vv) {
      const keyboardOffset = Math.max(0, window.innerHeight - vv.height - vv.offsetTop);
      document.documentElement.style.setProperty("--keyboard-offset", `${Math.round(keyboardOffset)}px`);
    }
    keepFocusedFieldVisible();
  }

  function keepFocusedFieldVisible() {
    const active = document.activeElement;
    if (!active || !(active instanceof HTMLElement)) return;
    if (!active.closest("#app")) return;
    const tag = active.tagName;
    if (tag !== "TEXTAREA" && tag !== "INPUT") return;
    if (tag === "INPUT" && active.type === "hidden") return;

    requestAnimationFrame(() => {
      if (active.id === "composer-input" || active.id === "new-prompt") {
        const scrollParent = active.closest(".new-options") || active.closest(".view");
        if (scrollParent) {
          const margin = 16;
          const parentRect = scrollParent.getBoundingClientRect();
          const fieldRect = active.getBoundingClientRect();
          if (fieldRect.bottom > parentRect.bottom - margin) {
            scrollParent.scrollTop += fieldRect.bottom - parentRect.bottom + margin;
          } else if (fieldRect.top < parentRect.top + margin) {
            scrollParent.scrollTop -= parentRect.top + margin - fieldRect.top;
          }
        }
        active.scrollIntoView({ block: "nearest", behavior: "smooth" });
        return;
      }
      active.scrollIntoView({ block: "nearest", behavior: "smooth" });
    });
  }

  async function disablePushSubscription() {
    try {
      const reg = await navigator.serviceWorker.getRegistration();
      const sub = await reg?.pushManager?.getSubscription();
      if (!sub) return;
      const endpoint = sub.endpoint;
      // Revoke server-side while the bearer token is still available.
      if (token) {
        await fetch("/api/push/subscribe", {
          method: "DELETE",
          headers: authHeaders({ "Content-Type": "application/json" }),
          body: JSON.stringify({ endpoint }),
        }).catch(() => {});
      }
      await sub.unsubscribe().catch(() => {});
      const btn = $("btn-notify");
      if (btn) btn.textContent = "Enable notifications";
    } catch (_) {
      // Best-effort — still clear local credentials below.
    }
  }

  async function exchangeLogin(body) {
    const res = await fetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.error || "login failed");
    }
    const json = await res.json();
    const sessionToken = json.sessionToken;
    if (!sessionToken) throw new Error("login failed");
    token = sessionToken;
    localStorage.setItem(SESSION_KEY, token);
    return json;
  }

  async function forgetToken(message) {
    await disablePushSubscription();
    localStorage.removeItem(SESSION_KEY);
    localStorage.removeItem(LEGACY_TOKEN_KEY);
    token = "";
    closeSocket();
    $("auth-error").textContent = message || "";
    $("auth-error").classList.toggle("hidden", !message);
    show("auth");
  }

  async function routeFromHash() {
    if (routePromise) return routePromise;
    routePromise = routeFromHashOnce().finally(() => {
      routePromise = null;
    });
    return routePromise;
  }

  async function routeFromHashOnce() {
    const { hash, fromCode, legacyToken } = loginParamsFromUrl();
    const chatMatch = hash.match(/^\/chat\/([^/]+)/);

    // QR / deep-link login always wins over a stale stored session.
    if (fromCode || legacyToken) {
      token = "";
      localStorage.removeItem(SESSION_KEY);
      try {
        if (fromCode) {
          await exchangeLogin({ code: fromCode.trim() });
        } else {
          await exchangeLogin({ token: legacyToken.trim() });
        }
        history.replaceState({}, "", location.pathname + (chatMatch ? `#/chat/${chatMatch[1]}` : "#/"));
      } catch (err) {
        $("auth-error").textContent = err.message || "Sign-in failed";
        $("auth-error").classList.remove("hidden");
        show("auth");
        return;
      }
    } else if (!token) {
      const legacyStored = localStorage.getItem(LEGACY_TOKEN_KEY);
      if (legacyStored) {
        try {
          await exchangeLogin({ token: legacyStored.trim() });
          localStorage.removeItem(LEGACY_TOKEN_KEY);
        } catch (_) {
          localStorage.removeItem(LEGACY_TOKEN_KEY);
        }
      }
      if (!token) {
        show("auth");
        return;
      }
    }
    if (hash.startsWith("/new")) {
      openNew();
    } else if (chatMatch) {
      openChat(decodeURIComponent(chatMatch[1]));
    } else {
      openList();
    }
  }

  function projectLabel(projectId) {
    if (!projectId) return "Inbox";
    const known = projectsById[projectId];
    if (known?.name) return known.name;
    const parts = projectId.split(/[\\/]/).filter(Boolean);
    return parts[parts.length - 1] || projectId;
  }

  function groupChats(chats) {
    const sorted = [...chats].sort((a, b) => {
      const pa = a.projectId || "";
      const pb = b.projectId || "";
      const sa = pa ? 1 : 0;
      const sb = pb ? 1 : 0;
      if (sa !== sb) return sa - sb;
      if (pa !== pb) return pa.localeCompare(pb);
      return (b.createdAtMillis || 0) - (a.createdAtMillis || 0);
    });
    const groups = [];
    for (const chat of sorted) {
      const key = chat.projectId || "";
      if (!groups.length || groups[groups.length - 1].key !== key) {
        groups.push({ key, label: projectLabel(key), chats: [] });
      }
      groups[groups.length - 1].chats.push(chat);
    }
    return groups;
  }

  async function loadProjects() {
    try {
      const projects = await api("/api/projects");
      projectsById = {};
      (projects || []).forEach((p) => {
        if (p?.id) projectsById[p.id] = p;
      });
      return projects || [];
    } catch (_) {
      return [];
    }
  }

  async function openList() {
    show("list");
    currentChatId = null;
    closeSocket();
    $("list-error").classList.add("hidden");
    $("list-empty").classList.add("hidden");
    try {
      await loadProjects();
      const chats = await api("/api/chats");
      const root = $("chat-list");
      root.innerHTML = "";
      if (!Array.isArray(chats) || chats.length === 0) {
        $("list-empty").classList.remove("hidden");
        return;
      }
      $("list-empty").classList.add("hidden");
      const groups = groupChats(chats);
      for (const group of groups) {
        const expanded = expandedProjects.has(group.key);
        const details = document.createElement("details");
        details.className = "group";
        details.open = expanded;
        const summary = document.createElement("summary");
        summary.className = "group-header";
        summary.innerHTML = `<span class="caret">${expanded ? "▾" : "▸"}</span>
          <span>${escapeHtml(group.label)} (${group.chats.length})</span>`;
        const body = document.createElement("div");
        body.className = "group-body";
        details.addEventListener("toggle", () => {
          if (details.open) expandedProjects.add(group.key);
          else expandedProjects.delete(group.key);
          summary.querySelector(".caret").textContent = details.open ? "▾" : "▸";
        });
        for (const chat of group.chats) {
          const btn = document.createElement("button");
          btn.type = "button";
          btn.className = "chat-row";
          btn.innerHTML = `
            <strong>${escapeHtml(chat.title || chat.id)}</strong>
            <div class="row-meta">
              <span class="badge">${escapeHtml(chat.status || "?")}</span>
              <span>${escapeHtml(chat.agent || "")}</span>
              ${chat.unread ? "<span>unread</span>" : ""}
            </div>`;
          btn.addEventListener("click", () => {
            location.hash = `#/chat/${encodeURIComponent(chat.id)}`;
          });
          body.appendChild(btn);
        }
        details.appendChild(summary);
        details.appendChild(body);
        root.appendChild(details);
      }
    } catch (err) {
      if (err.message !== "unauthorized") {
        $("list-error").textContent = err.message || "Failed to load chats";
        $("list-error").classList.remove("hidden");
      }
    }
  }

  function setChatLoading(loading) {
    chatLoading = loading;
    $("chat-loading").classList.toggle("hidden", !loading);
    $("transcript").classList.toggle("hidden", loading);
  }

  function setChatWorking(working, label) {
    chatWorking = !!working;
    if (chatMeta) {
      const status = working ? (label || "Working") : (chatMeta.status || "done");
      $("chat-meta").textContent = `${chatMeta.agent || ""} · ${status}`;
    }
    renderTranscript();
  }

  function shouldShowThinkingIndicator() {
    if (chatLoading || pendingInput) return false;
    if (!chatWorking && !optimisticUserText) return false;
    // Hide once a terminal result exists after the latest user message, or when Done.
    const status = (chatMeta?.status || "").toLowerCase();
    if (status === "done" || status === "error") return false;
    // If the newest visible event is already an assistant/tool/thinking chunk, keep a light
    // indicator only when still Working and no assistant text yet this turn.
    let sawUser = false;
    let sawAssistantAfterUser = false;
    for (let i = events.length - 1; i >= 0; i--) {
      const type = events[i]?.type;
      if (type === "user") {
        sawUser = true;
        break;
      }
      if (type === "assistant" || type === "tool" || type === "thinking" || type === "result") {
        sawAssistantAfterUser = true;
      }
    }
    if (optimisticUserText && !sawUser) return true;
    if (chatWorking && sawUser && !sawAssistantAfterUser) return true;
    if (chatWorking && !sawAssistantAfterUser) return true;
    return false;
  }

  async function openChat(id) {
    show("chat");
    currentChatId = id;
    events = [];
    pendingInput = null;
    optimisticUserText = null;
    slashCommands = [];
    hideSlashMenu();
    $("chat-error").classList.add("hidden");
    $("reconnect").classList.add("hidden");
    $("transcript").innerHTML = "";
    $("permission").classList.add("hidden");
    setChatLoading(true);
    setChatWorking(false);
    try {
      const body = await api(`/api/chats/${encodeURIComponent(id)}`);
      chatMeta = body.chat;
      $("chat-title").textContent = chatMeta.title || id;
      const status = chatMeta.status || "";
      $("chat-meta").textContent = `${chatMeta.agent || ""} · ${status}`;
      events = Array.isArray(body.events) ? body.events : [];
      pendingInput = chatMeta.userInputRequest || null;
      chatWorking = /^(working|starting|queued)$/i.test(status);
      setChatLoading(false);
      renderTranscript();
      renderPermission();
      await refreshSlashCommandsForChat();
      connectSocket(id);
    } catch (err) {
      setChatLoading(false);
      if (err.status === 409) {
        $("chat-error").textContent = err.message || "Unsupported chat";
        $("chat-error").classList.remove("hidden");
        return;
      }
      if (err.message !== "unauthorized") {
        $("chat-error").textContent = err.message || "Failed to load chat";
        $("chat-error").classList.remove("hidden");
      }
    }
  }

  function commandsFromEvents(list) {
    for (let i = list.length - 1; i >= 0; i--) {
      const ev = list[i];
      if (ev?.type === "commands" && Array.isArray(ev.commands)) {
        return ev.commands.map((c) => ({
          name: String(c.name || "").replace(/^[/\$]/, ""),
          description: c.description || "",
        })).filter((c) => c.name);
      }
    }
    return [];
  }

  async function fetchSlashCommands(agent, directory) {
    if (!agent) return [];
    try {
      const qs = new URLSearchParams({ agent });
      if (directory) qs.set("directory", directory);
      const list = await api(`/api/slash-commands?${qs}`);
      return Array.isArray(list) ? list : [];
    } catch (_) {
      return [];
    }
  }

  async function refreshSlashCommandsForChat() {
    if (!chatMeta) return;
    slashCommands = await fetchSlashCommands(chatMeta.agent || "", "");
    const fromEvents = commandsFromEvents(events);
    if (fromEvents.length) {
      const seen = new Set(slashCommands.map((c) => c.name.toLowerCase()));
      for (const cmd of fromEvents) {
        if (!seen.has(cmd.name.toLowerCase())) {
          slashCommands.push(cmd);
          seen.add(cmd.name.toLowerCase());
        }
      }
    }
  }

  async function refreshSlashCommandsForNew() {
    const agent = $("new-agent").value;
    slashCommands = await fetchSlashCommands(agent, "");
  }

  function connectSocket(id) {
    closeSocket();
    socketConnecting = true;
    $("reconnect").classList.add("hidden");
    const proto = location.protocol === "https:" ? "wss" : "ws";
    const base = `${proto}://${location.host}/ws/chats/${encodeURIComponent(id)}`;
    const useQuery = socketPreferQueryAuth;
    const url = useQuery ? `${base}?token=${encodeURIComponent(token)}` : base;
    const protocols = useQuery ? undefined : [`bearer.${token}`];
    socket = new WebSocket(url, protocols);
    socket.onopen = () => {
      socketConnecting = false;
      if (currentChatId === id) $("reconnect").classList.add("hidden");
    };
    socket.onmessage = (ev) => {
      let batch;
      try {
        batch = JSON.parse(ev.data);
      } catch (_) {
        return;
      }
      if (typeof batch.replaceFrom === "number") {
        events = events.slice(0, batch.replaceFrom).concat(batch.events || []);
      } else if (Array.isArray(batch.events) && batch.events.length) {
        // Incremental live updates only — initial snapshot must include replaceFrom
        // so REST-loaded history is not duplicated.
        events = events.concat(batch.events);
      }
      if (events.some((e) => e.type === "user")) {
        optimisticUserText = null;
      }
      if (Object.prototype.hasOwnProperty.call(batch, "userInputRequest")) {
        pendingInput = batch.userInputRequest;
      }
      if (batch.done) {
        pendingInput = null;
        chatWorking = false;
        if (chatMeta) chatMeta.status = batch.terminalStatus || "done";
        $("chat-meta").textContent = `${chatMeta?.agent || ""} · ${batch.terminalStatus || "done"}`;
      } else if (Array.isArray(batch.events) && batch.events.length && chatMeta) {
        chatWorking = true;
        if (chatMeta) chatMeta.status = "Working";
        $("chat-meta").textContent = `${chatMeta.agent || ""} · Working`;
      }
      const maybeCommands = commandsFromEvents(batch.events || []);
      if (maybeCommands.length) {
        refreshSlashCommandsForChat();
      }
      renderTranscript();
      renderPermission();
    };
    socket.onclose = (ev) => {
      socketConnecting = false;
      if (!socketPreferQueryAuth && ev.code !== 4401 && ev.code !== 4403 && ev.code !== 1008) {
        socketPreferQueryAuth = true;
        if (currentChatId === id) connectSocket(id);
        return;
      }
      if (ev.code === 4401 || ev.code === 4403 || ev.code === 1008) {
        forgetToken("Session expired or rejected. Sign in again.").catch(() => {});
        return;
      }
      if (currentChatId === id) {
        $("reconnect").classList.remove("hidden");
      }
    };
    socket.onerror = () => {
      socketConnecting = false;
      if (currentChatId === id && !socketConnecting) $("reconnect").classList.remove("hidden");
    };
  }

  function closeSocket() {
    if (socket) {
      socket.onclose = null;
      socket.onerror = null;
      socket.onopen = null;
      socket.onmessage = null;
      try { socket.close(); } catch (_) {}
      socket = null;
    }
  }

  function ensureSocket() {
    if (!currentChatId) return;
    if (!socket || socket.readyState === WebSocket.CLOSING || socket.readyState === WebSocket.CLOSED) {
      connectSocket(currentChatId);
    }
  }

  function isVisibleTranscriptEvent(type) {
    return type === "user" || type === "assistant" || type === "thinking" || type === "error" || type === "permission-resolved";
  }

  function renderTranscript() {
    const root = $("transcript");
    if (!root || chatLoading) return;
    const stick = root.scrollHeight - root.scrollTop - root.clientHeight < 80;
    root.innerHTML = "";
    let renderedUserMatch = false;
    for (const ev of events) {
      const type = ev.type || "raw";
      if (!isVisibleTranscriptEvent(type)) continue;
      const el = document.createElement("div");
      if (type === "user") {
        el.className = "bubble user";
        el.textContent = ev.text || "";
        if (optimisticUserText && ev.text === optimisticUserText) renderedUserMatch = true;
      } else if (type === "assistant") {
        el.className = "bubble assistant";
        el.textContent = ev.text || "";
      } else if (type === "thinking") {
        el.className = "bubble thinking-text";
        el.textContent = ev.text || "";
      } else if (type === "error") {
        el.className = "bubble error";
        el.textContent = ev.text || "error";
      } else if (type === "permission-resolved") {
        el.className = "bubble permission-resolved";
        el.textContent = `Permission ${ev.allowed ? "allowed" : "rejected"}: ${ev.optionId || "unknown option"}`;
        if (ev.note) el.textContent += ` (${ev.note})`;
      }
      root.appendChild(el);
    }
    if (optimisticUserText && !renderedUserMatch) {
      const el = document.createElement("div");
      el.className = "bubble user";
      el.textContent = optimisticUserText;
      root.appendChild(el);
    }
    if (shouldShowThinkingIndicator()) {
      const el = document.createElement("div");
      el.className = "bubble thinking";
      el.innerHTML = `<span class="thinking-dots" aria-hidden="true"><i></i><i></i><i></i></span><span>Working…</span>`;
      root.appendChild(el);
    }
    if (stick) root.scrollTop = root.scrollHeight;
  }

  function renderPermission() {
    const box = $("permission");
    if (!pendingInput || !pendingInput.questions || !pendingInput.questions.length) {
      box.classList.add("hidden");
      box.innerHTML = "";
      return;
    }
    box.classList.remove("hidden");
    const questions = pendingInput.questions;
    const answers = {};
    box.innerHTML = `<h2>Needs your input</h2><div id="permission-questions"></div>
      <button id="permission-submit" type="button" class="primary" disabled>Submit</button>`;
    const root = $("permission-questions");
    const submit = $("permission-submit");

    function syncSubmit() {
      const ready = questions.every((q) => {
        const value = (answers[q.id] || "").trim();
        return value.length > 0;
      });
      submit.disabled = !ready;
    }

    questions.forEach((q, index) => {
      const block = document.createElement("div");
      block.className = "permission-question";
      const heading = q.header || (questions.length > 1 ? `Question ${index + 1}` : "Needs your input");
      block.innerHTML = `<h3>${escapeHtml(heading)}</h3>
        <p>${escapeHtml(q.question || "")}</p>
        <div class="permission-options"></div>`;
      const opts = block.querySelector(".permission-options");
      const options = q.options || [];
      if (!options.length) {
        const input = document.createElement("textarea");
        input.rows = 2;
        input.placeholder = "Your answer";
        input.addEventListener("input", () => {
          answers[q.id] = input.value;
          syncSubmit();
        });
        opts.appendChild(input);
      } else {
        options.forEach((opt) => {
          const btn = document.createElement("button");
          btn.type = "button";
          btn.className = "option";
          btn.innerHTML = `<strong>${escapeHtml(opt.label || "")}</strong><div class="muted">${escapeHtml(opt.description || "")}</div>`;
          btn.addEventListener("click", () => {
            answers[q.id] = opt.label || "";
            opts.querySelectorAll(".option").forEach((el) => el.classList.remove("selected"));
            btn.classList.add("selected");
            // Single option-only question: submit immediately (matches desktop permission UX).
            if (questions.length === 1) {
              respond(pendingInput.id, { [q.id]: answers[q.id] });
              return;
            }
            syncSubmit();
          });
          opts.appendChild(btn);
        });
      }
      root.appendChild(block);
    });

    submit.addEventListener("click", () => {
      const payload = {};
      for (const q of questions) {
        payload[q.id] = (answers[q.id] || "").trim();
      }
      if (Object.values(payload).some((v) => !v)) return;
      respond(pendingInput.id, payload);
    });
    // Hide Submit for the single option-only quick path; keep it for multi / freeform.
    const onlySingleOptionQuestion =
      questions.length === 1 && Array.isArray(questions[0].options) && questions[0].options.length > 0;
    submit.hidden = onlySingleOptionQuestion;
    syncSubmit();
  }

  async function respond(requestId, answers) {
    try {
      ensureSocket();
      setChatWorking(true);
      await api(`/api/chats/${encodeURIComponent(currentChatId)}/respond`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ requestId, answers }),
      });
      pendingInput = null;
      renderPermission();
    } catch (err) {
      if (err.message !== "unauthorized") {
        $("chat-error").textContent = err.message || "Respond failed";
        $("chat-error").classList.remove("hidden");
      }
    }
  }

  async function loadAgents() {
    try {
      const agents = await api("/api/agents");
      const select = $("new-agent");
      if (!select) return agents;
      const previous = select.value;
      select.innerHTML = "";
      (agents || [])
        .filter((a) => a?.webChat)
        .forEach((a) => {
          const opt = document.createElement("option");
          opt.value = a.id || a.cliName || "";
          opt.textContent = a.label || a.id || "";
          select.appendChild(opt);
        });
      if ([...select.options].some((o) => o.value === previous)) {
        select.value = previous;
      }
      return agents;
    } catch (_) {
      return [];
    }
  }

  async function loadModelsForAgent(agentId) {
    const select = $("new-model");
    if (!select || !agentId) return;
    const previous = select.value;
    newChatRequiresModel = false;
    newChatDefaultRuntime = null;
    select.innerHTML = "";
    select.disabled = true;
    try {
      const body = await api(`/api/models?agent=${encodeURIComponent(agentId)}`);
      newChatRequiresModel = !!body.requiresModel;
      newChatDefaultRuntime = body.defaultRuntime || null;
      const models = Array.isArray(body.models) ? body.models : [];
      if (!models.length && newChatRequiresModel) {
        const opt = document.createElement("option");
        opt.value = "";
        opt.textContent = "No models found";
        select.appendChild(opt);
        select.disabled = true;
        return;
      }
      models.forEach((model) => {
        const opt = document.createElement("option");
        opt.value = model.id ?? "";
        opt.textContent = model.label || model.id || "Model";
        select.appendChild(opt);
      });
      const preferred = [previous, body.defaultModel || ""]
        .find((value) => [...select.options].some((o) => o.value === value));
      if (preferred != null) {
        select.value = preferred;
      } else if (select.options.length) {
        select.selectedIndex = 0;
      }
      select.disabled = false;
    } catch (_) {
      const opt = document.createElement("option");
      opt.value = "";
      opt.textContent = "Provider default";
      select.appendChild(opt);
      select.disabled = false;
    }
  }

  async function openNew() {
    show("new");
    $("new-error").classList.add("hidden");
    hideSlashMenu();
    try {
      await loadAgents();
      const projects = await loadProjects();
      const select = $("new-project");
      const previous = select.value;
      select.innerHTML = `<option value="">Inbox (no project)</option>`;
      projects.forEach((p) => {
        const opt = document.createElement("option");
        opt.value = p.id;
        opt.textContent = p.name || p.id;
        select.appendChild(opt);
      });
      if ([...select.options].some((o) => o.value === previous)) {
        select.value = previous;
      }
      await loadModelsForAgent($("new-agent").value);
      await refreshSlashCommandsForNew();
    } catch (_) {}
  }

  function escapeHtml(value) {
    return String(value ?? "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function findSlashQuery(text) {
    const match = /(?:^|\s)\/([A-Za-z0-9:_-]*)$/.exec(text);
    if (!match) return null;
    const start = match.index + (match[0].startsWith("/") ? 0 : 1);
    return { start, query: match[1] };
  }

  function matchingSlashCommands(query) {
    const q = (query || "").toLowerCase();
    return slashCommands.filter((cmd) => {
      const name = (cmd.name || "").toLowerCase();
      const desc = (cmd.description || "").toLowerCase();
      return !q || name.includes(q) || desc.includes(q);
    }).slice(0, 12);
  }

  function hideSlashMenu() {
    ["slash-menu", "new-slash-menu"].forEach((id) => {
      const menu = $(id);
      if (!menu) return;
      menu.classList.add("hidden");
      menu.innerHTML = "";
    });
    slashActiveIndex = 0;
    slashInput = null;
    slashMenuEl = null;
  }

  function applySlashCommand(name) {
    const input = slashInput;
    if (!input) return;
    const text = input.value;
    const caret = input.selectionStart ?? text.length;
    const found = findSlashQuery(text.slice(0, caret));
    if (!found) return;
    const before = text.slice(0, found.start);
    const after = text.slice(caret);
    input.value = `${before}/${name} ${after}`.replace(/[ \t]+$/u, " ");
    input.focus();
    const next = found.start + name.length + 2;
    input.setSelectionRange(next, next);
    hideSlashMenu();
  }

  function renderSlashMenuFor(input, menu) {
    slashInput = input;
    slashMenuEl = menu;
    const caret = input.selectionStart ?? input.value.length;
    const found = findSlashQuery(input.value.slice(0, caret));
    if (!found) {
      hideSlashMenu();
      return;
    }
    const matches = matchingSlashCommands(found.query);
    if (!matches.length) {
      hideSlashMenu();
      return;
    }
    if (slashActiveIndex >= matches.length) slashActiveIndex = 0;
    menu.innerHTML = "";
    matches.forEach((cmd, index) => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = `slash-item${index === slashActiveIndex ? " active" : ""}`;
      btn.setAttribute("role", "option");
      btn.innerHTML = `<strong>/${escapeHtml(cmd.name)}</strong><span>${escapeHtml(cmd.description || "")}</span>`;
      btn.addEventListener("mousedown", (ev) => {
        ev.preventDefault();
        slashInput = input;
        slashMenuEl = menu;
        applySlashCommand(cmd.name);
      });
      menu.appendChild(btn);
    });
    menu.classList.remove("hidden");
  }

  function bindSlashField(input, menu) {
    if (!input || !menu) return;
    input.addEventListener("focus", () => {
      slashInput = input;
      slashMenuEl = menu;
      renderSlashMenuFor(input, menu);
    });
    input.addEventListener("input", () => renderSlashMenuFor(input, menu));
    input.addEventListener("keydown", (ev) => {
      if (menu.classList.contains("hidden")) return;
      const items = [...menu.querySelectorAll(".slash-item")];
      if (!items.length) return;
      if (ev.key === "ArrowDown") {
        ev.preventDefault();
        slashActiveIndex = (slashActiveIndex + 1) % items.length;
        renderSlashMenuFor(input, menu);
      } else if (ev.key === "ArrowUp") {
        ev.preventDefault();
        slashActiveIndex = (slashActiveIndex - 1 + items.length) % items.length;
        renderSlashMenuFor(input, menu);
      } else if (ev.key === "Enter" || ev.key === "Tab") {
        ev.preventDefault();
        const active = items[slashActiveIndex];
        const name = active?.querySelector("strong")?.textContent?.replace(/^\//, "") || "";
        if (name) {
          slashInput = input;
          applySlashCommand(name);
        }
      } else if (ev.key === "Escape") {
        hideSlashMenu();
      }
    });
  }

  function urlBase64ToUint8Array(base64String) {
    const padding = "=".repeat((4 - (base64String.length % 4)) % 4);
    const base64 = (base64String + padding).replace(/-/g, "+").replace(/_/g, "/");
    const raw = atob(base64);
    const out = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
    return out;
  }

  function canUseWebPush() {
    // Browsers require a secure context (HTTPS or localhost) for PushManager.
    // Plain HTTP on a LAN/VPN origin cannot register for push — document that
    // users who want notifications must terminate TLS in front of andyd.
    return (
      window.isSecureContext === true &&
      "serviceWorker" in navigator &&
      "PushManager" in window &&
      "Notification" in window
    );
  }

  function syncNotifyButton() {
    const btn = $("btn-notify");
    if (!btn) return;
    if (!canUseWebPush()) {
      btn.hidden = true;
      btn.title =
        "Push notifications require HTTPS (use Tailscale Serve or a reverse proxy in front of Andy). Chat still works over plain HTTP.";
      return;
    }
    btn.hidden = false;
  }

  async function enableNotifications() {
    if (!canUseWebPush()) {
      alert(
        "Push notifications require HTTPS. Chat works over plain HTTP; put Tailscale Serve or a reverse proxy in front of Andy if you want notifications.",
      );
      return;
    }
    const permission = await Notification.requestPermission();
    if (permission !== "granted") return;
    const reg = await navigator.serviceWorker.register("/sw.js");
    await navigator.serviceWorker.ready;
    const { publicKey } = await api("/api/push/vapid-key");
    const sub = await reg.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: urlBase64ToUint8Array(publicKey),
    });
    await api("/api/push/subscribe", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(sub.toJSON()),
    });
    $("btn-notify").textContent = "Notifications on";
  }

  $("token-save").addEventListener("click", async () => {
    const value = $("token-input").value.trim();
    if (!value) {
      $("auth-error").textContent = "Token or login code required";
      $("auth-error").classList.remove("hidden");
      return;
    }
    $("auth-error").classList.add("hidden");
    try {
      const body = value.length <= 24 ? { code: value } : { token: value };
      await exchangeLogin(body);
      $("token-input").value = "";
      location.hash = "#/";
      routeFromHash();
    } catch (err) {
      $("auth-error").textContent = err.message || "Sign-in failed";
      $("auth-error").classList.remove("hidden");
    }
  });

  $("btn-forget").addEventListener("click", () => {
    if (confirm("Log out of Andy on this device? You'll need the access token again.")) {
      forgetToken().catch(() => {});
    }
  });
  $("btn-new").addEventListener("click", () => { location.hash = "#/new"; });
  $("btn-back").addEventListener("click", () => { location.hash = "#/"; });
  $("btn-new-back").addEventListener("click", () => { location.hash = "#/"; });
  $("btn-reconnect").addEventListener("click", () => {
    if (currentChatId) connectSocket(currentChatId);
  });
  $("btn-notify").addEventListener("click", () => enableNotifications().catch((e) => alert(e.message)));

  $("new-project").addEventListener("change", () => {
    refreshSlashCommandsForNew();
  });
  $("new-agent").addEventListener("change", () => {
    loadModelsForAgent($("new-agent").value).then(() => refreshSlashCommandsForNew());
  });

  bindSlashField($("composer-input"), $("slash-menu"));
  bindSlashField($("new-prompt"), $("new-slash-menu"));

  $("app").addEventListener("focusin", (ev) => {
    const target = ev.target;
    if (
      target instanceof HTMLTextAreaElement ||
      (target instanceof HTMLInputElement && target.type !== "hidden")
    ) {
      syncViewportHeight();
    }
  });

  $("composer").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    hideSlashMenu();
    const message = $("composer-input").value.trim();
    if (!message || !currentChatId) return;
    $("composer-input").value = "";
    optimisticUserText = message;
    setChatWorking(true);
    try {
      ensureSocket();
      await api(`/api/chats/${encodeURIComponent(currentChatId)}/reply`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message }),
      });
      ensureSocket();
    } catch (err) {
      optimisticUserText = null;
      setChatWorking(false);
      renderTranscript();
      if (err.message !== "unauthorized") {
        $("chat-error").textContent = err.message || "Send failed";
        $("chat-error").classList.remove("hidden");
      }
    }
  });

  $("new-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    hideSlashMenu();
    $("new-error").classList.add("hidden");
    const projectId = $("new-project").value.trim();
    const model = $("new-model").value.trim();
    if (newChatRequiresModel && !model) {
      $("new-error").textContent = "Choose a model to continue";
      $("new-error").classList.remove("hidden");
      return;
    }
    const body = {
      prompt: $("new-prompt").value.trim(),
      agent: $("new-agent").value,
      autonomy: $("new-autonomy").value,
      projectId: projectId || undefined,
    };
    if (model) body.model = model;
    if (newChatDefaultRuntime) body.runtime = newChatDefaultRuntime;
    try {
      const json = await api("/api/chats/start", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      location.hash = `#/chat/${encodeURIComponent(json.id)}`;
    } catch (err) {
      if (err.message !== "unauthorized") {
        $("new-error").textContent = err.message || "Start failed";
        $("new-error").classList.remove("hidden");
      }
    }
  });

  window.addEventListener("hashchange", routeFromHash);
  if (!isIOS) {
    window.addEventListener("resize", syncViewportHeight);
    if (window.visualViewport) {
      window.visualViewport.addEventListener("resize", syncViewportHeight);
      window.visualViewport.addEventListener("scroll", syncViewportHeight);
    }
    syncViewportHeight();
  }

  // Only register the service worker in a secure context. On plain HTTP LAN
  // origins registration fails and is not needed for chat.
  if (canUseWebPush()) {
    navigator.serviceWorker.register("/sw.js").catch(() => {});
  }
  syncNotifyButton();
  syncSafeAreaTop();
  requestAnimationFrame(syncSafeAreaTop);
  window.addEventListener("pageshow", syncSafeAreaTop);
  window.addEventListener("orientationchange", syncSafeAreaTop);

  routeFromHash();
})();
