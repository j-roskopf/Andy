(() => {
  const TOKEN_KEY = "andy.networkAccessToken";
  const $ = (id) => document.getElementById(id);

  const views = {
    auth: $("view-auth"),
    list: $("view-list"),
    chat: $("view-chat"),
    new: $("view-new"),
  };

  let token = localStorage.getItem(TOKEN_KEY) || "";
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
  let chatWorking = false;
  let chatLoading = false;
  let optimisticUserText = null;

  function show(name) {
    Object.entries(views).forEach(([key, el]) => {
      el.classList.toggle("hidden", key !== name);
    });
  }

  function syncViewportHeight() {
    const vv = window.visualViewport;
    const height = Math.round(vv ? vv.height : window.innerHeight);
    document.documentElement.style.setProperty("--app-height", `${height}px`);
    // Keep focused composer visible above the soft keyboard / browser chrome.
    const active = document.activeElement;
    if (active && (active.id === "composer-input" || active.id === "new-prompt")) {
      requestAnimationFrame(() => {
        active.scrollIntoView({ block: "nearest", inline: "nearest" });
        const transcript = $("transcript");
        if (transcript && !views.chat.classList.contains("hidden")) {
          transcript.scrollTop = transcript.scrollHeight;
        }
      });
    }
  }

  function authHeaders(extra = {}) {
    return Object.assign({ Authorization: `Bearer ${token}` }, extra);
  }

  async function api(path, options = {}) {
    const res = await fetch(path, {
      ...options,
      headers: authHeaders(options.headers || {}),
    });
    if (res.status === 401) {
      forgetToken("Session expired — enter the current access token.");
      throw new Error("unauthorized");
    }
    return res;
  }

  function forgetToken(message) {
    localStorage.removeItem(TOKEN_KEY);
    token = "";
    closeSocket();
    $("auth-error").textContent = message || "";
    $("auth-error").classList.toggle("hidden", !message);
    show("auth");
  }

  function routeFromHash() {
    const hash = location.hash.replace(/^#/, "") || "/";
    const chatMatch = hash.match(/^\/chat\/([^/]+)/);
    if (!token) {
      // QR deep-link: /?token=... or /#/?token=...
      const params = new URLSearchParams(location.search);
      const hashParams = new URLSearchParams(hash.split("?")[1] || "");
      const fromQuery = params.get("token") || hashParams.get("token");
      if (fromQuery) {
        token = fromQuery.trim();
        localStorage.setItem(TOKEN_KEY, token);
        history.replaceState({}, "", location.pathname + (chatMatch ? `#/chat/${chatMatch[1]}` : "#/"));
      } else {
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
      const res = await api("/api/projects");
      const projects = await res.json();
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
    try {
      await loadProjects();
      const res = await api("/api/chats");
      const chats = await res.json();
      const root = $("chat-list");
      root.innerHTML = "";
      if (!Array.isArray(chats) || chats.length === 0) {
        $("list-empty").classList.remove("hidden");
        return;
      }
      $("list-empty").classList.add("hidden");
      for (const group of groupChats(chats)) {
        const section = document.createElement("div");
        section.className = "group";
        const expanded = expandedProjects.has(group.key);
        const header = document.createElement("button");
        header.type = "button";
        header.className = "group-header";
        header.innerHTML = `<span class="caret">${expanded ? "▾" : "▸"}</span>
          <span>${escapeHtml(group.label)} (${group.chats.length})</span>`;
        const body = document.createElement("div");
        body.className = `group-body${expanded ? "" : " collapsed"}`;
        header.addEventListener("click", () => {
          if (expandedProjects.has(group.key)) expandedProjects.delete(group.key);
          else expandedProjects.add(group.key);
          const open = expandedProjects.has(group.key);
          body.classList.toggle("collapsed", !open);
          header.querySelector(".caret").textContent = open ? "▾" : "▸";
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
        section.appendChild(header);
        section.appendChild(body);
        root.appendChild(section);
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
      const res = await api(`/api/chats/${encodeURIComponent(id)}`);
      if (res.status === 409) {
        const body = await res.json().catch(() => ({}));
        setChatLoading(false);
        $("chat-error").textContent = body.error || "Unsupported chat";
        $("chat-error").classList.remove("hidden");
        return;
      }
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const body = await res.json();
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
      const res = await api(`/api/slash-commands?${qs}`);
      if (!res.ok) return [];
      const list = await res.json();
      return Array.isArray(list) ? list : [];
    } catch (_) {
      return [];
    }
  }

  async function refreshSlashCommandsForChat() {
    if (!chatMeta) return;
    const directory = chatMeta.cwd || chatMeta.originDir || "";
    slashCommands = await fetchSlashCommands(chatMeta.agent || "", directory);
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
    const opt = $("new-project").selectedOptions[0];
    const directory = opt?.dataset?.directory || "";
    slashCommands = await fetchSlashCommands(agent, directory);
  }

  function connectSocket(id) {
    closeSocket();
    const proto = location.protocol === "https:" ? "wss" : "ws";
    const url = `${proto}://${location.host}/ws/chats/${encodeURIComponent(id)}?token=${encodeURIComponent(token)}`;
    socket = new WebSocket(url);
    socket.onopen = () => {
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
      if (ev.code === 4401 || ev.code === 1008) {
        forgetToken("Access token rejected. Enter the current token.");
        return;
      }
      if (currentChatId === id) {
        $("reconnect").classList.remove("hidden");
      }
    };
    socket.onerror = () => {
      if (currentChatId === id) $("reconnect").classList.remove("hidden");
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

  function renderTranscript() {
    const root = $("transcript");
    if (!root || chatLoading) return;
    const stick = root.scrollHeight - root.scrollTop - root.clientHeight < 80;
    root.innerHTML = "";
    let renderedUserMatch = false;
    for (const ev of events) {
      const el = document.createElement("div");
      const type = ev.type || "raw";
      if (type === "user") {
        el.className = "bubble user";
        el.textContent = ev.text || "";
        if (optimisticUserText && ev.text === optimisticUserText) renderedUserMatch = true;
      } else if (type === "assistant") {
        el.className = "bubble assistant";
        el.textContent = ev.text || "";
      } else if (type === "thinking") {
        el.className = "bubble meta";
        el.textContent = `thinking: ${(ev.text || "").slice(0, 240)}`;
      } else if (type === "tool" || type === "tool-result") {
        el.className = "bubble tool";
        el.textContent = `${ev.toolName || "tool"} — ${ev.summary || ev.detail || type}`;
      } else if (type === "permission") {
        el.className = "bubble meta";
        el.textContent = `permission: ${ev.question || ev.toolName || ""}`;
      } else if (type === "error") {
        el.className = "bubble meta";
        el.textContent = ev.text || "error";
      } else if (type === "result") {
        el.className = "bubble meta";
        el.textContent = ev.finalText || (ev.success ? "done" : "failed");
      } else {
        continue;
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
      const res = await api(`/api/chats/${encodeURIComponent(currentChatId)}/respond`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ requestId, answers }),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      pendingInput = null;
      renderPermission();
    } catch (err) {
      if (err.message !== "unauthorized") {
        $("chat-error").textContent = err.message || "Respond failed";
        $("chat-error").classList.remove("hidden");
      }
    }
  }

  async function openNew() {
    show("new");
    $("new-error").classList.add("hidden");
    hideSlashMenu();
    try {
      const projects = await loadProjects();
      const select = $("new-project");
      const previous = select.value;
      select.innerHTML = `<option value="">Inbox (no project)</option>`;
      projects.forEach((p) => {
        const opt = document.createElement("option");
        opt.value = p.id;
        opt.textContent = p.name || p.id;
        opt.dataset.directory = p.directory || "";
        select.appendChild(opt);
      });
      if ([...select.options].some((o) => o.value === previous)) {
        select.value = previous;
      }
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
    const keyRes = await api("/api/push/vapid-key");
    const { publicKey } = await keyRes.json();
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

  $("token-save").addEventListener("click", () => {
    const value = $("token-input").value.trim();
    if (!value) {
      $("auth-error").textContent = "Token required";
      $("auth-error").classList.remove("hidden");
      return;
    }
    token = value;
    localStorage.setItem(TOKEN_KEY, token);
    $("auth-error").classList.add("hidden");
    location.hash = "#/";
    routeFromHash();
  });

  $("btn-forget").addEventListener("click", () => {
    if (confirm("Log out of Andy on this device? You'll need the access token again.")) {
      forgetToken();
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
    refreshSlashCommandsForNew();
  });

  bindSlashField($("composer-input"), $("slash-menu"));
  bindSlashField($("new-prompt"), $("new-slash-menu"));

  $("composer-input").addEventListener("focus", () => {
    syncViewportHeight();
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
      const res = await api(`/api/chats/${encodeURIComponent(currentChatId)}/reply`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message }),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
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
    const projectOpt = $("new-project").selectedOptions[0];
    const directory = projectOpt?.dataset?.directory || "";
    const body = {
      prompt: $("new-prompt").value.trim(),
      agent: $("new-agent").value,
      directory: directory || undefined,
      autonomy: $("new-autonomy").value,
      title: $("new-title").value.trim() || undefined,
      projectId: projectId || undefined,
    };
    try {
      const res = await api("/api/chats/start", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      const json = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(json.error || `HTTP ${res.status}`);
      location.hash = `#/chat/${encodeURIComponent(json.id)}`;
    } catch (err) {
      if (err.message !== "unauthorized") {
        $("new-error").textContent = err.message || "Start failed";
        $("new-error").classList.remove("hidden");
      }
    }
  });

  window.addEventListener("hashchange", routeFromHash);
  window.addEventListener("resize", syncViewportHeight);
  if (window.visualViewport) {
    window.visualViewport.addEventListener("resize", syncViewportHeight);
    window.visualViewport.addEventListener("scroll", syncViewportHeight);
  }
  syncViewportHeight();

  // Only register the service worker in a secure context. On plain HTTP LAN
  // origins registration fails and is not needed for chat.
  if (canUseWebPush()) {
    navigator.serviceWorker.register("/sw.js").catch(() => {});
  }
  syncNotifyButton();

  routeFromHash();
})();
