import {
  Adb,
  AdbBanner,
  AdbDaemonTransport,
  AdbServerClient,
  AdbServerTransport,
} from "@yume-chan/adb";
import { AdbDaemonWebUsbDeviceManager } from "@yume-chan/adb-daemon-webusb";
import {
  AdbScrcpyClient,
  AdbScrcpyOptions3_3_3,
} from "@yume-chan/adb-scrcpy";
import {
  AndroidKeyCode,
  AndroidKeyEventAction,
  AndroidKeyEventMeta,
  AndroidMotionEventAction,
  AndroidMotionEventButton,
  ScrcpyPointerId,
} from "@yume-chan/scrcpy";
import {
  BitmapVideoFrameRenderer,
  WebCodecsVideoDecoder,
  WebGLVideoFrameRenderer,
} from "@yume-chan/scrcpy-decoder-webcodecs";
import { TinyH264Decoder } from "@yume-chan/scrcpy-decoder-tinyh264";
import { MaybeConsumable, ReadableStream, TextDecoderStream, TransformStream, WritableStream } from "@yume-chan/stream-extra";

const BRIDGE_URL = "ws://127.0.0.1:8037/adb";
const SCRCPY_SERVER_PATH = "/data/local/tmp/andy-scrcpy-server";
const SCRCPY_VERSION = "4.0";
const BRIDGE_HELP = [
  `Failed to connect ${BRIDGE_URL}. Instructions:`,
  "  adb start-server",
  "  curl -fL https://github.com/j-roskopf/Andy/releases/latest/download/andy-tracebox -o andy-tracebox",
  "  chmod +x ./andy-tracebox",
  "  ./andy-tracebox",
].join("\n");

const state = {
  transport: "none",
  serverClient: undefined,
  adbs: new Map(),
  deviceMetadata: new Map(),
  usbDevices: new Map(),
  logcat: undefined,
  mirror: undefined,
  nextSessionId: 0,
  mirrorHostId: undefined,
  mirrorHighlight: undefined,
  files: new Map(),
  bugCapture: undefined,
  pendingBug: undefined,
  bugPlayback: undefined,
};

function json(value) {
  return JSON.stringify(value, (_, item) =>
    typeof item === "bigint" ? item.toString() : item,
  );
}

function errorMessage(error) {
  return error instanceof Error ? error.message : String(error);
}

function bridgeError(error) {
  const detail = errorMessage(error);
  if (detail.startsWith(BRIDGE_HELP)) return error instanceof Error ? error : new Error(detail);
  return new Error(`${BRIDGE_HELP}\n\nBrowser detail: ${detail}`);
}

function openCredentialDatabase() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open("andy-web-credentials", 1);
    request.onupgradeneeded = () => request.result.createObjectStore("vault");
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error("Unable to open the WebUSB credential vault."));
  });
}

async function credentialValue(key) {
  const database = await openCredentialDatabase();
  try {
    return await idbRequest(database.transaction("vault", "readonly").objectStore("vault").get(key));
  } finally {
    database.close();
  }
}

async function saveCredentialValues(values) {
  const database = await openCredentialDatabase();
  try {
    const transaction = database.transaction("vault", "readwrite");
    const store = transaction.objectStore("vault");
    for (const [key, value] of Object.entries(values)) store.put(value, key);
    await new Promise((resolve, reject) => {
      transaction.oncomplete = resolve;
      transaction.onerror = () => reject(transaction.error ?? new Error("Unable to save the WebUSB credential."));
    });
  } finally {
    database.close();
  }
}

class AndyWebCredentialStore {
  constructor(appName = "Andy") { this.appName = appName; }

  async generateKey() {
    const pair = await crypto.subtle.generateKey({
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-1",
    }, true, ["sign", "verify"]);
    const privateBytes = new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey));
    const wrappingKey = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ciphertext = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, wrappingKey, privateBytes);
    await saveCredentialValues({ wrappingKey, iv, ciphertext });
    return { buffer: privateBytes, name: `${this.appName}@${location.hostname}` };
  }

  async *iterateKeys() {
    const [wrappingKey, iv, ciphertext] = await Promise.all([
      credentialValue("wrappingKey"), credentialValue("iv"), credentialValue("ciphertext"),
    ]);
    if (!wrappingKey || !iv || !ciphertext) return;
    const privateBytes = new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv }, wrappingKey, ciphertext));
    yield { buffer: privateBytes, name: `${this.appName}@${location.hostname}` };
  }
}

class PerfettoAdbWebSocketConnector {
  async connect({ signal } = {}) {
    let socket;
    try {
      socket = new WebSocket(BRIDGE_URL);
      socket.binaryType = "arraybuffer";
      await new Promise((resolve, reject) => {
        const onOpen = () => {
          cleanup();
          resolve();
        };
        const onError = () => {
          cleanup();
          reject(new Error("The tracebox WebSocket bridge refused the connection."));
        };
        const onAbort = () => {
          cleanup();
          socket.close();
          reject(signal.reason ?? new DOMException("Aborted", "AbortError"));
        };
        const cleanup = () => {
          socket.removeEventListener("open", onOpen);
          socket.removeEventListener("error", onError);
          signal?.removeEventListener("abort", onAbort);
        };
        socket.addEventListener("open", onOpen, { once: true });
        socket.addEventListener("error", onError, { once: true });
        signal?.addEventListener("abort", onAbort, { once: true });
      });
    } catch (error) {
      throw bridgeError(error);
    }

    let readableController;
    const readable = new ReadableStream({
      start(controller) {
        readableController = controller;
        socket.addEventListener("message", (event) => {
          const data = event.data;
          if (data instanceof ArrayBuffer) {
            controller.enqueue(new Uint8Array(data));
          } else if (data instanceof Blob) {
            data.arrayBuffer().then((buffer) => controller.enqueue(new Uint8Array(buffer)));
          }
        });
        socket.addEventListener("close", () => {
          try {
            controller.close();
          } catch (_) {
            // A pending reader may already have closed or cancelled the stream.
          }
        }, { once: true });
        socket.addEventListener("error", () => socket.close(), { once: true });
      },
      cancel() {
        socket.close();
      },
    });

    const writable = new MaybeConsumable.WritableStream({
      write(chunk) {
        if (socket.readyState !== WebSocket.OPEN) {
          throw bridgeError("The bridge connection closed while writing.");
        }
        socket.send(chunk);
      },
      close() {
        socket.close();
      },
      abort() {
        socket.close();
      },
    });

    const closed = new Promise((resolve) => {
      socket.addEventListener("close", () => resolve(undefined), { once: true });
    });

    return {
      readable,
      writable,
      closed,
      close() {
        socket.close();
        try {
          readableController?.close();
        } catch (_) {
          // The socket close listener may have won the race.
        }
      },
    };
  }

  addReverseTunnel() {
    throw new Error("ADB reverse tunnels are unavailable through the browser bridge.");
  }

  removeReverseTunnel() {}

  clearReverseTunnels() {}
}

async function shellAdb(adb, args) {
  const command = Array.isArray(args) ? args : [String(args)];
  if (adb.subprocess.shellProtocol) {
    const result = await adb.subprocess.shellProtocol.spawnWaitText(command);
    return {
      exitCode: result.exitCode,
      stdout: result.stdout,
      stderr: result.stderr,
    };
  }
  return {
    exitCode: 0,
    stdout: await adb.subprocess.noneProtocol.spawnWaitText(command),
    stderr: "",
  };
}

async function requireSupportedDevice(adb) {
  let pending = state.deviceMetadata.get(adb.serial);
  if (!pending) {
    pending = (async () => {
      const [apiText, model, product, device, abi, sizeResult] = await Promise.all([
        adb.getProp("ro.build.version.sdk"),
        adb.getProp("ro.product.model"),
        adb.getProp("ro.product.name"),
        adb.getProp("ro.product.device"),
        adb.getProp("ro.product.cpu.abi"),
        shellAdb(adb, ["wm", "size"]),
      ]);
      const api = Number.parseInt(apiText.trim(), 10);
      if (!Number.isFinite(api) || api < 30) {
        throw new Error(`Andy Web requires Android API 30 or newer; ${adb.serial} reports API ${apiText.trim() || "unknown"}.`);
      }
      const size = /Physical size:\s*(\d+)x(\d+)/.exec(sizeResult.stdout);
      return {
        serial: adb.serial,
        state: "device",
        model: model || adb.banner?.model || "Android device",
        product,
        device,
        abi,
        api,
        width: size ? Number(size[1]) : 0,
        height: size ? Number(size[2]) : 0,
      };
    })();
    state.deviceMetadata.set(adb.serial, pending);
  }
  try {
    return { ...await pending, transport: state.transport };
  } catch (error) {
    if (state.deviceMetadata.get(adb.serial) === pending) state.deviceMetadata.delete(adb.serial);
    throw error;
  }
}

async function createServerAdb(client, device) {
  const { transportId, features } = await client.getDeviceFeatures({ transportId: device.transportId });
  const banner = new AdbBanner(device.state, device.product, device.model, device.device, features);
  // AdbServerClient.createAdb also starts a wait-for-disconnect request. Across
  // the WebSocket bridge that idle request can end, and the library surfaces the
  // ended stream as an unhandled rejection. Device presence is refreshed
  // explicitly in Andy, so keep this transport alive until refresh or reconnect.
  const disconnected = new Promise(() => {});
  return new Adb(new AdbServerTransport(client, device.serial, banner, transportId, disconnected));
}

async function stopLogcat() {
  const logcat = state.logcat;
  state.logcat = undefined;
  if (!logcat) return;
  await Promise.resolve(logcat.process.kill()).catch(() => {});
}

async function resetAdbConnection() {
  await stopLogcat();
  await stopMirror();
  for (const adb of state.adbs.values()) await adb.close().catch(() => {});
  state.adbs.clear();
  state.deviceMetadata.clear();
  state.usbDevices.clear();
  state.serverClient = undefined;
  state.transport = "none";
}

function webUsbConnectionError(error) {
  const detail = errorMessage(error);
  if (/user gesture|permission request/i.test(detail)) {
    return new Error([
      "The browser blocked the WebUSB device chooser.",
      "",
      "Click Use WebUSB directly in Andy, then select the Android device in the browser prompt.",
      "",
      `Browser detail: ${detail}`,
    ].join("\n"));
  }
  const contention = /in use|claim|busy|access denied|unable to open/i.test(detail);
  const heading = contention
    ? "WebUSB could not claim the Android USB interface because another program is using it."
    : "WebUSB could not open the selected Android device.";
  return new Error([
    heading,
    "",
    "Try these steps:",
    "  1. Close Android Studio, scrcpy, desktop Andy, and other browser tabs connected to this phone.",
    "  2. Release the phone from the host ADB server:",
    "       adb kill-server",
    "  3. Unplug and reconnect the USB cable, unlock the phone, then choose Use WebUSB again.",
    "  4. Keep adb stopped while using WebUSB. Press Use ADB + WebSocket when you want to switch back.",
    "",
    `Browser detail: ${detail}`,
  ].join("\n"));
}

async function refreshServerDevices() {
  if (!state.serverClient) return [];
  const devices = await state.serverClient.getDevices();
  const connected = devices.filter((device) => device.state === "device");
  const activeSerials = new Set(connected.map((device) => device.serial));
  for (const serial of state.adbs.keys()) {
    if (!activeSerials.has(serial)) {
      state.adbs.delete(serial);
      state.deviceMetadata.delete(serial);
    }
  }
  return Promise.all(connected.map(async (device) => {
    let adb = state.adbs.get(device.serial);
    if (!adb) {
      adb = await createServerAdb(state.serverClient, device);
      state.adbs.set(device.serial, adb);
    }
    return requireSupportedDevice(adb);
  }));
}

export async function webAdbConnectWebSocket() {
  await resetAdbConnection();
  let lastError;
  const delays = [250, 500, 1000];
  for (let attempt = 0; attempt <= delays.length; attempt += 1) {
    try {
      const client = new AdbServerClient(new PerfettoAdbWebSocketConnector());
      await client.getVersion();
      state.serverClient = client;
      state.transport = "websocket";
      const devices = await refreshServerDevices();
      return json({ ok: true, transport: state.transport, devices });
    } catch (error) {
      lastError = error;
      state.serverClient = undefined;
      state.transport = "none";
      if (attempt === delays.length || document.visibilityState !== "visible") break;
      await new Promise((resolve) => setTimeout(resolve, delays[attempt]));
    }
  }
  throw bridgeError(lastError);
}

export async function webAdbRequestWebUsb() {
  const manager = AdbDaemonWebUsbDeviceManager.BROWSER;
  if (!manager) throw new Error("WebUSB is not available in this browser. Use desktop Chrome or Edge, or use the local ADB bridge.");
  let selected;
  try {
    selected = await manager.requestDevice();
  } catch (error) {
    await resetAdbConnection();
    state.transport = "webusb";
    throw webUsbConnectionError(error);
  }
  if (!selected) return json({ ok: false, cancelled: true, devices: [] });
  await resetAdbConnection();
  state.transport = "webusb";
  try {
    const connection = await selected.connect();
    const transport = await AdbDaemonTransport.authenticate({
      serial: selected.serial,
      connection,
      credentialStore: new AndyWebCredentialStore("Andy"),
    });
    const adb = new Adb(transport);
    const metadata = await requireSupportedDevice(adb);
    state.adbs.set(adb.serial, adb);
    state.usbDevices.set(adb.serial, selected);
    return json({ ok: true, transport: state.transport, devices: [metadata] });
  } catch (error) {
    throw webUsbConnectionError(error);
  }
}

function deleteDatabase(name) {
  return new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = resolve;
    request.onblocked = resolve;
    request.onerror = () => reject(request.error ?? new Error(`Unable to delete IndexedDB database ${name}.`));
  });
}

export async function webAdbForgetWebUsbAuthorization() {
  await resetAdbConnection();
  const manager = AdbDaemonWebUsbDeviceManager.BROWSER;
  if (manager) {
    for (const device of await manager.getDevices()) await device.raw.forget?.().catch(() => {});
  }
  await deleteDatabase("andy-web-credentials");
  return json({ ok: true });
}

export async function webAdbListDevices() {
  const devices = state.transport === "websocket"
    ? await refreshServerDevices()
    : await Promise.all([...state.adbs.values()].map(requireSupportedDevice));
  return json({ transport: state.transport, devices });
}

export async function webAdbShell(serial, argsJson) {
  const adb = state.adbs.get(serial);
  if (!adb) throw new Error(`Android device ${serial} is not connected.`);
  return json(await shellAdb(adb, JSON.parse(argsJson)));
}

export async function webAdbStartLogcat(serial) {
  if (state.logcat?.serial === serial) return json({ ok: true, reused: true, sessionId: state.logcat.sessionId });
  await stopLogcat();
  const adb = state.adbs.get(serial);
  if (!adb) throw new Error(`Android device ${serial} is not connected.`);
  if (!adb.subprocess.shellProtocol) throw new Error("Streaming logcat requires the Android shell protocol.");
  const process = await adb.subprocess.shellProtocol.spawn(["logcat", "-v", "threadtime"]);
  const logcat = { serial, sessionId: String(++state.nextSessionId), process, partial: "", lines: [], error: undefined };
  state.logcat = logcat;
  process.stdout
    .pipeThrough(new TextDecoderStream())
    .pipeTo(new WritableStream({
      write(chunk) {
        const pieces = (logcat.partial + chunk).split("\n");
        logcat.partial = pieces.pop() ?? "";
        logcat.lines.push(...pieces);
        if (logcat.lines.length > 2_000) logcat.lines.splice(0, logcat.lines.length - 2_000);
        const capture = state.bugCapture;
        if (capture && capture.serial === serial) {
          capture.logLines.push(...pieces);
          if (capture.logLines.length > 5_000) capture.logLines.splice(0, capture.logLines.length - 5_000);
        }
      },
    }))
    .catch((error) => { if (state.logcat === logcat) logcat.error = errorMessage(error); });
  process.exited.then(
    (exitCode) => { if (state.logcat === logcat && exitCode !== 0) logcat.error = `logcat exited with code ${exitCode}`; },
    (error) => { if (state.logcat === logcat) logcat.error = errorMessage(error); },
  );
  return json({ ok: true, sessionId: logcat.sessionId });
}

export function webAdbDrainLogcat(sessionId) {
  const logcat = state.logcat;
  if (!logcat || logcat.sessionId !== sessionId) return json({ lines: [] });
  return json({ lines: logcat.lines.splice(0), error: logcat.error });
}

export async function webAdbStopLogcat(sessionId) {
  if (state.logcat?.sessionId === sessionId) await stopLogcat();
}

function downloadBlob(blob, suggestedName) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = suggestedName;
  anchor.style.display = "none";
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

async function downloadStreamToOpfs(source, suggestedName, mimeType = "application/octet-stream") {
  const origin = await navigator.storage.getDirectory();
  const temporary = await origin.getDirectoryHandle("downloads", { create: true });
  const fileName = `andy-download-${crypto.randomUUID()}`;
  const handle = await temporary.getFileHandle(fileName, { create: true });
  const writable = await handle.createWritable();
  const reader = source.getReader();
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      await writable.write(value);
    }
    await writable.close();
  } catch (error) {
    await writable.abort(error).catch(() => {});
    throw error;
  } finally {
    reader.releaseLock();
  }
  const file = await handle.getFile();
  downloadBlob(file.type ? file : new Blob([file], { type: mimeType }), suggestedName);
  setTimeout(() => temporary.removeEntry(fileName).catch(() => {}), 60_000);
  return file.size;
}

export async function webPickFiles(allowMultiple) {
  return await new Promise((resolve) => {
    const input = document.createElement("input");
    input.type = "file";
    input.multiple = allowMultiple;
    input.style.display = "none";
    input.addEventListener("change", () => {
      const refs = [];
      for (const file of input.files ?? []) {
        const id = `web-file-${crypto.randomUUID()}`;
        state.files.set(id, file);
        refs.push(`${id}:${file.name}`);
      }
      input.remove();
      resolve(json(refs));
    }, { once: true });
    input.addEventListener("cancel", () => {
      input.remove();
      resolve("[]");
    }, { once: true });
    document.body.appendChild(input);
    input.click();
  });
}

function fileForReference(reference) {
  const id = reference.substring(0, reference.indexOf(":"));
  const file = state.files.get(id);
  if (!file) throw new Error("The selected browser file is no longer available. Choose it again.");
  return file;
}

export async function webAdbPushFile(serial, reference, remotePath) {
  const adb = state.adbs.get(serial);
  if (!adb) throw new Error(`Android device ${serial} is not connected.`);
  const file = fileForReference(reference);
  const sync = await adb.sync();
  try {
    await sync.write({ filename: remotePath, file: file.stream() });
  } finally {
    await sync.dispose();
  }
  return json({ ok: true, name: file.name, size: file.size, remotePath });
}

export async function webAdbInstallFile(serial, reference, replace) {
  const file = fileForReference(reference);
  const safeName = file.name.replace(/[^A-Za-z0-9._-]/g, "_");
  const remotePath = `/data/local/tmp/andy-${Date.now()}-${safeName}`;
  await webAdbPushFile(serial, reference, remotePath);
  const adb = state.adbs.get(serial);
  try {
    return json(await shellAdb(adb, ["pm", "install", ...(replace ? ["-r"] : []), remotePath]));
  } finally {
    await adb.rm(remotePath, { force: true }).catch(() => {});
  }
}

export async function webAdbPullFile(serial, remotePath, suggestedName) {
  const adb = state.adbs.get(serial);
  if (!adb) throw new Error(`Android device ${serial} is not connected.`);
  const sync = await adb.sync();
  try {
    const size = await downloadStreamToOpfs(
      sync.read(remotePath),
      suggestedName || remotePath.split("/").pop() || "android-file",
    );
    return json({ ok: true, size });
  } finally {
    await sync.dispose();
  }
}

export async function webAdbDownloadCommand(serial, argsJson, suggestedName, mimeType) {
  const adb = state.adbs.get(serial);
  if (!adb) throw new Error(`Android device ${serial} is not connected.`);
  const process = await adb.subprocess.noneProtocol.spawn(JSON.parse(argsJson));
  const [size] = await Promise.all([
    downloadStreamToOpfs(process.output, suggestedName, mimeType),
    process.exited,
  ]);
  return json({ ok: true, size });
}

export async function webAdbDownloadBugReport(serial, suggestedName) {
  const adb = state.adbs.get(serial);
  if (!adb) throw new Error(`Android device ${serial} is not connected.`);
  const result = await shellAdb(adb, ["bugreportz", "-p"]);
  const remotePath = /(?:OK:|BEGIN:)(\S+)/.exec(result.stdout)?.[1]
    ?? /([^\s]+\.zip)/.exec(result.stdout)?.[1];
  if (!remotePath) throw new Error(result.stderr || result.stdout || "Android did not produce a bug report archive.");
  try {
    return await webAdbPullFile(serial, remotePath, suggestedName);
  } finally {
    await adb.rm(remotePath, { force: true }).catch(() => {});
  }
}

export async function webAdbAccessibility(serial) {
  const adb = state.adbs.get(serial);
  if (!adb) throw new Error(`Android device ${serial} is not connected.`);
  await shellAdb(adb, ["uiautomator", "dump", "/sdcard/andy-window.xml"]);
  const result = await shellAdb(adb, ["cat", "/sdcard/andy-window.xml"]);
  const document = new DOMParser().parseFromString(result.stdout, "application/xml");
  const root = document.querySelector("node");
  if (!root) return "null";
  const parseNode = (element, id) => {
    const attribute = (name) => element.getAttribute(name) || null;
    const bool = (name, fallback = false) => {
      const value = element.getAttribute(name);
      return value === null ? fallback : value === "true";
    };
    return {
      id,
      className: attribute("class"),
      packageName: attribute("package"),
      resourceId: attribute("resource-id"),
      text: attribute("text"),
      contentDescription: attribute("content-desc"),
      hint: attribute("hint"),
      bounds: attribute("bounds"),
      clickable: bool("clickable"),
      longClickable: bool("long-clickable"),
      focusable: bool("focusable"),
      focused: bool("focused"),
      enabled: bool("enabled", true),
      selected: bool("selected"),
      checkable: bool("checkable"),
      checked: bool("checked"),
      scrollable: bool("scrollable"),
      password: bool("password"),
      visible: bool("visible-to-user", true),
      attributes: Object.fromEntries([...element.attributes].map((item) => [item.name, item.value])),
      children: [...element.children].filter((child) => child.tagName === "node").map((child, index) => parseNode(child, `${id}.${index}`)),
    };
  };
  return json(parseNode(root, "0"));
}

class AdbScrcpyOptions4_0 extends AdbScrcpyOptions3_3_3 {
  constructor(init) {
    super({
      ...init,
      video: true,
      audio: false,
      tunnelForward: true,
      sendCodecMeta: false,
      sendDeviceMeta: false,
      sendDummyByte: false,
      sendFrameMeta: true,
    }, { version: SCRCPY_VERSION });
  }

  serialize() {
    return super.serialize()
      .filter((argument) => !argument.startsWith("send_codec_meta="))
      .concat("send_stream_meta=false");
  }

  createMediaStreamTransformer() {
    // scrcpy 4.0 kept the 12-byte packet header but inserted a session flag,
    // shifting CONFIG from bit 63 to 62 and KEY_FRAME from bit 62 to 61.
    // Stream metadata is disabled above, so session packets cannot appear here.
    const configFlag = 1n << 62n;
    const keyFrameFlag = 1n << 61n;
    const ptsMask = keyFrameFlag - 1n;
    let pending = new Uint8Array(0);
    return new TransformStream({
      transform(chunk, controller) {
        if (pending.length === 0) {
          pending = chunk;
        } else {
          const combined = new Uint8Array(pending.length + chunk.length);
          combined.set(pending);
          combined.set(chunk, pending.length);
          pending = combined;
        }
        while (pending.length >= 12) {
          const view = new DataView(pending.buffer, pending.byteOffset, pending.byteLength);
          const ptsAndFlags = view.getBigUint64(0, false);
          const size = view.getUint32(8, false);
          if (pending.length < 12 + size) return;
          const data = pending.slice(12, 12 + size);
          if ((ptsAndFlags & configFlag) !== 0n) {
            controller.enqueue({ type: "configuration", data });
          } else {
            controller.enqueue({
              type: "data",
              keyframe: (ptsAndFlags & keyFrameFlag) !== 0n,
              pts: ptsAndFlags & ptsMask,
              data,
            });
          }
          pending = pending.slice(12 + size);
        }
      },
    });
  }
}

function installMirrorInput(canvas, mirror) {
  canvas.tabIndex = 0;
  canvas.style.touchAction = "none";
  canvas.style.outline = "none";

  const devicePoint = (event) => {
    const rect = canvas.getBoundingClientRect();
    return {
      x: Math.max(0, Math.min(mirror.width - 1, Math.round((event.clientX - rect.left) * mirror.width / rect.width))),
      y: Math.max(0, Math.min(mirror.height - 1, Math.round((event.clientY - rect.top) * mirror.height / rect.height))),
    };
  };

  const injectPointer = (event, action) => {
    if (!mirror.client.controller || !mirror.width || !mirror.height) return undefined;
    const { x, y } = devicePoint(event);
    mirror.client.controller.injectTouch({
      action,
      pointerId: ScrcpyPointerId.Finger,
      pointerX: x,
      pointerY: y,
      videoWidth: mirror.width,
      videoHeight: mirror.height,
      pressure: action === AndroidMotionEventAction.Up ? 0 : 1,
      actionButton: AndroidMotionEventButton.Primary,
      buttons: action === AndroidMotionEventAction.Up ? AndroidMotionEventButton.None : AndroidMotionEventButton.Primary,
    }).catch((error) => { mirror.error = errorMessage(error); });
    return { x, y };
  };

  canvas.addEventListener("pointerdown", (event) => {
    canvas.focus({ preventScroll: true });
    canvas.setPointerCapture(event.pointerId);
    const point = injectPointer(event, AndroidMotionEventAction.Down);
    if (point) {
      mirror.pointerGesture = { ...point, pointerId: event.pointerId, startedAt: Date.now(), moved: false };
    }
    event.preventDefault();
  });
  canvas.addEventListener("pointermove", (event) => {
    if (canvas.hasPointerCapture(event.pointerId)) {
      injectPointer(event, AndroidMotionEventAction.Move);
      if (mirror.pointerGesture?.pointerId === event.pointerId) mirror.pointerGesture.moved = true;
    }
  });
  const finish = (event) => {
    const point = injectPointer(event, AndroidMotionEventAction.Up);
    const gesture = mirror.pointerGesture?.pointerId === event.pointerId ? mirror.pointerGesture : undefined;
    mirror.pointerGesture = undefined;
    const capture = state.bugCapture;
    if (point && gesture && capture) {
      const dx = point.x - gesture.x;
      const dy = point.y - gesture.y;
      const distance = Math.round(Math.sqrt(dx * dx + dy * dy));
      const duration = Math.max(0, Date.now() - gesture.startedAt);
      const isSwipe = gesture.moved && distance >= 24;
      const direction = Math.abs(dx) > Math.abs(dy)
        ? (dx > 0 ? "right" : "left")
        : (dy > 0 ? "down" : "up");
      const timestampMillis = Date.now();
      capture.actions.push({
        id: `web-pointer-${timestampMillis}-${capture.actions.length}`,
        timestampMillis,
        kind: "input",
        label: isSwipe ? `Swipe ${direction}` : `Tap ${point.x},${point.y}`,
        detail: isSwipe
          ? `${distance}px · ${duration}ms · ${gesture.x},${gesture.y} -> ${point.x},${point.y}`
          : undefined,
      });
    }
    if (canvas.hasPointerCapture(event.pointerId)) canvas.releasePointerCapture(event.pointerId);
  };
  canvas.addEventListener("pointerup", finish);
  canvas.addEventListener("pointercancel", finish);
  canvas.addEventListener("contextmenu", (event) => event.preventDefault());
}

async function stopMirror() {
  const mirror = state.mirror;
  state.mirror = undefined;
  if (!mirror) return;
  clearInterval(mirror.statsTimer);
  mirror.decoder?.dispose();
  await mirror.client?.close().catch(() => {});
  mirror.canvas?.remove();
}

function attachMirrorToActiveHost(mirror = state.mirror) {
  const host = state.mirrorHostId ? document.getElementById(state.mirrorHostId) : undefined;
  if (!host || !mirror) return false;
  const highlight = ensureMirrorHighlight(host);
  if (mirror.canvas.parentElement !== host) host.replaceChildren(mirror.canvas, highlight);
  renderMirrorHighlight();
  return true;
}

function ensureMirrorHighlight(host) {
  let highlight = host.querySelector("[data-andy-mirror-highlight]");
  if (highlight) return highlight;
  highlight = document.createElement("div");
  highlight.dataset.andyMirrorHighlight = "true";
  highlight.style.position = "absolute";
  highlight.style.pointerEvents = "none";
  highlight.style.zIndex = "2";
  highlight.style.display = "none";
  highlight.style.boxSizing = "border-box";
  highlight.style.border = "2px solid #d06b4c";
  highlight.style.background = "rgba(208, 107, 76, 0.28)";
  return highlight;
}

function renderMirrorHighlight() {
  const config = state.mirrorHighlight;
  const host = config ? document.getElementById(config.hostId) : undefined;
  if (!host) return;
  const highlight = ensureMirrorHighlight(host);
  const match = /^\s*\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]\s*$/.exec(config.bounds);
  if (!match || config.sourceWidth <= 0 || config.sourceHeight <= 0) {
    highlight.style.display = "none";
    return;
  }
  const hostWidth = host.clientWidth;
  const hostHeight = host.clientHeight;
  const scale = Math.min(hostWidth / config.sourceWidth, hostHeight / config.sourceHeight);
  const fittedWidth = config.sourceWidth * scale;
  const fittedHeight = config.sourceHeight * scale;
  const fittedLeft = (hostWidth - fittedWidth) / 2;
  const fittedTop = (hostHeight - fittedHeight) / 2;
  const left = Number(match[1]);
  const top = Number(match[2]);
  const right = Number(match[3]);
  const bottom = Number(match[4]);
  highlight.style.display = "block";
  highlight.style.left = `${fittedLeft + left * scale}px`;
  highlight.style.top = `${fittedTop + top * scale}px`;
  highlight.style.width = `${Math.max(0, right - left) * scale}px`;
  highlight.style.height = `${Math.max(0, bottom - top) * scale}px`;
}

export async function webAdbStartMirror(serial, configJson) {
  const adb = state.adbs.get(serial);
  if (!adb) throw new Error(`Android device ${serial} is not connected.`);
  const config = JSON.parse(configJson);
  const useHardwareDecoder = WebCodecsVideoDecoder.isSupported;
  if (!useHardwareDecoder && (config.codec ?? "h264") !== "h264") {
    throw new Error("This origin requires the H.264 decoder. Use H.264 or open Andy over HTTPS for hardware decoding.");
  }
  const requestedMaxSize = config.maxSize ?? 720;
  const configKey = JSON.stringify({
    maxSize: useHardwareDecoder ? requestedMaxSize : (requestedMaxSize === 0 ? 720 : Math.min(requestedMaxSize, 720)),
    bitRate: useHardwareDecoder ? (config.bitRate ?? 4_000_000) : Math.min(config.bitRate ?? 4_000_000, 4_000_000),
    maxFps: useHardwareDecoder ? (config.maxFps ?? 60) : Math.min(config.maxFps ?? 60, 30),
    codec: config.codec ?? "h264",
    decoder: useHardwareDecoder ? "webcodecs" : "tinyh264",
  });
  const effectiveConfig = JSON.parse(configKey);
  const current = state.mirror;
  if (current?.serial === serial && current.configKey === configKey && !current.error) {
    return json({
      ok: true,
      reused: true,
      serial,
      renderer: current.renderer.constructor.name,
      codec: current.codec,
      width: current.width,
      height: current.height,
      sessionId: current.sessionId,
    });
  }
  clearBugPlayback();
  await stopMirror();
  const response = await fetch("/scrcpy/scrcpy-server");
  if (!response.ok || !response.body) throw new Error(`Unable to load the bundled scrcpy server (${response.status}).`);
  await AdbScrcpyClient.pushServer(adb, response.body, SCRCPY_SERVER_PATH);

  const options = new AdbScrcpyOptions4_0({
    maxSize: effectiveConfig.maxSize,
    videoBitRate: effectiveConfig.bitRate,
    maxFps: effectiveConfig.maxFps,
    videoCodec: effectiveConfig.codec,
    videoCodecOptions: useHardwareDecoder ? undefined : "profile=1,level=2048",
    powerOn: true,
  });
  const client = await AdbScrcpyClient.start(adb, SCRCPY_SERVER_PATH, options);
  const serverOutput = [];
  client.output.pipeTo(new WritableStream({
    write(line) {
      serverOutput.push(line);
      if (serverOutput.length > 40) serverOutput.shift();
    },
  })).catch((error) => {
    if (state.mirror) state.mirror.error = errorMessage(error);
  });
  const video = await client.videoStream;
  if (!video) throw new Error("scrcpy started without a video stream.");

  const canvas = document.createElement("canvas");
  canvas.dataset.andyMirror = "true";
  canvas.style.width = "100%";
  canvas.style.height = "100%";
  canvas.style.objectFit = "contain";
  canvas.style.display = "block";
  canvas.style.background = "#000";
  let renderer;
  let decoder;
  if (useHardwareDecoder) {
    renderer = WebGLVideoFrameRenderer.isSupported
      ? new WebGLVideoFrameRenderer(canvas, false)
      : new BitmapVideoFrameRenderer(canvas);
    decoder = new WebCodecsVideoDecoder({ codec: video.metadata.codec, renderer });
  } else {
    decoder = new TinyH264Decoder({ canvas });
    renderer = decoder;
  }
  const mirror = {
    serial,
    sessionId: String(++state.nextSessionId),
    configKey,
    codec: video.metadata.codec,
    client,
    decoder,
    renderer,
    canvas,
    width: video.width,
    height: video.height,
    startedAt: performance.now(),
    lastStatsAt: performance.now(),
    lastFrames: 0,
    lastDecodedFrames: 0,
    displayedFps: 0,
    decodedFps: 0,
    error: undefined,
    serverOutput,
  };
  state.mirror = mirror;
  // A resolution change replaces the canvas while Compose keeps the same HTML
  // host. Reattach immediately instead of waiting for an interop update that may
  // never run because the host composable itself did not change.
  attachMirrorToActiveHost(mirror);
  client.exited.then(() => {
    if (state.mirror === mirror) {
      const exitOutput = serverOutput.join("\n") || "scrcpy server exited";
      mirror.error = mirror.error ? `${mirror.error}\n${exitOutput}` : exitOutput;
      console.error("Andy scrcpy server exited", mirror.error);
    }
  });
  video.sizeChanged(({ width, height }) => {
    mirror.width = width;
    mirror.height = height;
  });
  decoder.sizeChanged(({ width, height }) => {
    mirror.width = width;
    mirror.height = height;
  });
  installMirrorInput(canvas, mirror);
  video.stream.pipeTo(decoder.writable).catch((error) => {
    if (state.mirror === mirror) mirror.error = errorMessage(error);
  });
  mirror.statsTimer = setInterval(() => {
    const now = performance.now();
    const elapsed = now - mirror.lastStatsAt;
    const frames = decoder.framesRendered;
    const decodedFrames = frames + decoder.framesSkipped;
    mirror.displayedFps = elapsed > 0 ? (frames - mirror.lastFrames) * 1000 / elapsed : 0;
    mirror.decodedFps = elapsed > 0 ? (decodedFrames - mirror.lastDecodedFrames) * 1000 / elapsed : 0;
    mirror.lastFrames = frames;
    mirror.lastDecodedFrames = decodedFrames;
    mirror.lastStatsAt = now;
    canvas.dataset.displayedFps = mirror.displayedFps.toFixed(1);
    canvas.dataset.decodedFps = mirror.decodedFps.toFixed(1);
    canvas.dataset.framesRendered = String(frames);
    canvas.dataset.framesSkipped = String(decoder.framesSkipped);
    canvas.dataset.error = mirror.error ?? "";
    canvas.dataset.serverOutput = mirror.serverOutput.slice(-12).join("\n");
  }, 1000);
  return json({
    ok: true,
    serial,
    renderer: renderer.constructor.name,
    codec: video.metadata.codec,
    width: mirror.width,
    height: mirror.height,
    sessionId: mirror.sessionId,
  });
}

export function webAdbAttachMirror(hostId) {
  const host = document.getElementById(hostId);
  if (!host) return false;
  state.mirrorHostId = hostId;
  if (state.bugPlayback) {
    attachBugPlayback(host);
    return true;
  }
  return attachMirrorToActiveHost();
}

export function webAdbSetMirrorHighlight(hostId, bounds, sourceWidth, sourceHeight) {
  state.mirrorHighlight = { hostId, bounds, sourceWidth, sourceHeight };
  renderMirrorHighlight();
}

export function webAdbDetachMirror(hostId) {
  const host = document.getElementById(hostId);
  const mirror = state.mirror;
  if (host && mirror?.canvas.parentElement === host) host.removeChild(mirror.canvas);
  if (host && state.bugPlayback?.video.parentElement === host) host.removeChild(state.bugPlayback.video);
  if (state.mirrorHostId === hostId) {
    state.mirrorHostId = undefined;
    state.mirrorHighlight = undefined;
  }
}

export async function webAdbStopMirror(sessionId) {
  if (state.mirror?.sessionId === sessionId) await stopMirror();
}

export async function webAdbSendMirrorInput(inputJson) {
  const mirror = state.mirror;
  const controller = mirror?.client.controller;
  if (!controller) throw new Error("The Live mirror is not connected.");
  const input = JSON.parse(inputJson);
  const injectKey = async (keyCode) => {
    const base = { keyCode, repeat: 0, metaState: AndroidKeyEventMeta.None };
    await controller.injectKeyCode({ ...base, action: AndroidKeyEventAction.Down });
    await controller.injectKeyCode({ ...base, action: AndroidKeyEventAction.Up });
  };
  switch (input.type) {
    case "key": await injectKey(input.keyCode); break;
    case "text": await controller.injectText(input.value); break;
    case "back": await injectKey(AndroidKeyCode.AndroidBack); break;
    case "home": await injectKey(AndroidKeyCode.AndroidHome); break;
    case "recents": await injectKey(AndroidKeyCode.AndroidAppSwitch); break;
    case "power": await injectKey(AndroidKeyCode.Power); break;
    case "rotate": await controller.rotateDevice(); break;
    default: throw new Error(`Unsupported mirror input: ${input.type}`);
  }
  return json({ ok: true });
}

export function webAdbMirrorStats() {
  const mirror = state.mirror;
  if (!mirror) return json({ connected: false, displayedFps: 0, decodedFps: 0, framesRendered: 0, framesSkipped: 0 });
  return json({
    connected: true,
    serial: mirror.serial,
    displayedFps: mirror.displayedFps,
    decodedFps: mirror.decodedFps,
    framesRendered: mirror.decoder.framesRendered,
    framesSkipped: mirror.decoder.framesSkipped,
    width: mirror.width,
    height: mirror.height,
    elapsedMillis: performance.now() - mirror.startedAt,
    renderer: mirror.renderer.constructor.name,
    error: mirror.error,
    serverOutput: mirror.serverOutput.slice(-12),
  });
}

export async function webStorageStatus() {
  const estimate = await navigator.storage?.estimate?.() ?? {};
  const resourceOrigins = [...new Set(performance.getEntriesByType("resource").flatMap((entry) => {
    try { return [new URL(entry.name, location.href).origin]; } catch (_) { return []; }
  }))].sort();
  return json({
    persisted: await navigator.storage?.persisted?.() ?? false,
    usageBytes: estimate.usage ?? 0,
    quotaBytes: estimate.quota ?? 0,
    resourceOrigins,
  });
}

export async function webStorageRequestPersistence() {
  return await navigator.storage?.persist?.() ?? false;
}

export async function webStorageClearAll() {
  await stopLogcat();
  await stopMirror();
  for (const adb of state.adbs.values()) await adb.close().catch(() => {});
  state.adbs.clear();
  state.deviceMetadata.clear();
  await webAdbForgetWebUsbAuthorization();
  for (const database of await indexedDB.databases?.() ?? []) {
    if (database.name) await deleteDatabase(database.name);
  }
  localStorage.clear();
  return json({ ok: true });
}

function openAndyDatabase() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open("andy-web", 1);
    request.onupgradeneeded = () => {
      const database = request.result;
      if (!database.objectStoreNames.contains("settings")) database.createObjectStore("settings");
      if (!database.objectStoreNames.contains("bugs")) database.createObjectStore("bugs", { keyPath: "id" });
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error("Unable to open Andy IndexedDB storage."));
  });
}

function idbRequest(request) {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error("IndexedDB operation failed."));
  });
}

export async function webWorkspaceLoad() {
  const database = await openAndyDatabase();
  try {
    return await idbRequest(database.transaction("settings", "readonly").objectStore("settings").get("workspace")) ?? "";
  } finally {
    database.close();
  }
}

export async function webWorkspaceSave(value) {
  const database = await openAndyDatabase();
  try {
    await idbRequest(database.transaction("settings", "readwrite").objectStore("settings").put(value, "workspace"));
    return json({ ok: true });
  } finally {
    database.close();
  }
}

function mediaRecorderMimeType() {
  return ["video/webm;codecs=vp8", "video/webm;codecs=vp9", "video/webm"]
    .find((candidate) => MediaRecorder.isTypeSupported(candidate)) ?? "";
}

export async function webBugStart(serial) {
  if (!state.mirror?.canvas) throw new Error("Open Live and connect the device before starting a bug capture.");
  if (state.bugCapture) throw new Error("A bug capture is already active.");
  await webAdbStartLogcat(serial).catch(() => {});
  const stream = state.mirror.canvas.captureStream(15);
  const chunks = [];
  const mimeType = mediaRecorderMimeType();
  const recorder = new MediaRecorder(stream, mimeType ? { mimeType, videoBitsPerSecond: 1_500_000 } : undefined);
  const startedAt = Date.now();
  recorder.ondataavailable = (event) => { if (event.data.size) chunks.push(event.data); };
  recorder.start(1000);
  state.pendingBug = undefined;
  state.bugCapture = {
    serial,
    stream,
    recorder,
    chunks,
    startedAt,
    mimeType: recorder.mimeType || mimeType || "video/webm",
    actions: [],
    logLines: [],
  };
  return json({ startedAt, serial, mimeType: state.bugCapture.mimeType });
}

export async function webBugStop() {
  const capture = state.bugCapture;
  if (!capture) return json(state.pendingBug ?? { startedAt: Date.now(), endedAt: Date.now(), durationMillis: 0, sizeBytes: 0 });
  const endedAt = Date.now();
  await new Promise((resolve) => {
    capture.recorder.addEventListener("stop", resolve, { once: true });
    capture.recorder.stop();
  });
  capture.stream.getTracks().forEach((track) => track.stop());
  const blob = new Blob(capture.chunks, { type: capture.mimeType });
  state.pendingBug = {
    blob,
    startedAt: capture.startedAt,
    endedAt,
    durationMillis: Math.max(0, endedAt - capture.startedAt),
    sizeBytes: blob.size,
    mimeType: blob.type || "video/webm",
    actions: capture.actions,
    logLines: capture.logLines,
  };
  state.bugCapture = undefined;
  return json({ ...state.pendingBug, blob: undefined });
}

async function bugRoot() {
  const origin = await navigator.storage.getDirectory();
  return origin.getDirectoryHandle("bugs", { create: true });
}

async function writeOpfsFile(directory, name, value) {
  const handle = await directory.getFileHandle(name, { create: true });
  const writable = await handle.createWritable();
  await writable.write(value);
  await writable.close();
}

async function bugDirectory(id, create = false) {
  return (await bugRoot()).getDirectoryHandle(id, { create });
}

export async function webBugSave(reportJson, logcat, accessibilityJson) {
  if (state.bugCapture) await webBugStop();
  const report = JSON.parse(reportJson);
  const directory = await bugDirectory(report.id, true);
  if (state.pendingBug?.blob) await writeOpfsFile(directory, "capture.webm", state.pendingBug.blob);
  await Promise.all([
    writeOpfsFile(directory, "report.json", reportJson),
    writeOpfsFile(directory, "actions.json", JSON.stringify(report.actions, null, 2)),
    writeOpfsFile(directory, "logcat.txt", logcat),
    writeOpfsFile(directory, "accessibility.json", accessibilityJson || "null"),
  ]);
  const database = await openAndyDatabase();
  try {
    await idbRequest(database.transaction("bugs", "readwrite").objectStore("bugs").put(report));
  } finally {
    database.close();
  }
  state.pendingBug = undefined;
  return reportJson;
}

export async function webBugList() {
  const database = await openAndyDatabase();
  try {
    const reports = await idbRequest(database.transaction("bugs", "readonly").objectStore("bugs").getAll());
    const knownIds = new Set(reports.map((report) => report.id));
    const root = await bugRoot();
    for await (const [id, handle] of root.entries()) {
      if (handle.kind !== "directory" || knownIds.has(id)) continue;
      try {
        const report = JSON.parse(await (await readBugFile(id, "report.json")).text());
        reports.push(report);
        knownIds.add(id);
        await idbRequest(database.transaction("bugs", "readwrite").objectStore("bugs").put(report));
      } catch (_) {
        // Ignore incomplete directories left by an interrupted capture.
      }
    }
    reports.sort((left, right) => right.capturedAtMillis - left.capturedAtMillis);
    return json(reports);
  } finally {
    database.close();
  }
}

export async function webBugLoad(id) {
  const database = await openAndyDatabase();
  try {
    return json(await idbRequest(database.transaction("bugs", "readonly").objectStore("bugs").get(id)) ?? null);
  } finally {
    database.close();
  }
}

async function readBugFile(id, name) {
  const file = await (await bugDirectory(id)).getFileHandle(name).then((handle) => handle.getFile());
  return file;
}

export async function webBugLoadLog(id) {
  return await (await readBugFile(id, "logcat.txt")).text();
}

export async function webBugDelete(id) {
  if (state.bugPlayback?.id === id) clearBugPlayback();
  const database = await openAndyDatabase();
  try {
    await idbRequest(database.transaction("bugs", "readwrite").objectStore("bugs").delete(id));
  } finally {
    database.close();
  }
  await (await bugRoot()).removeEntry(id, { recursive: true }).catch(() => {});
  return true;
}

export async function webBugExport(id) {
  const report = JSON.parse(await webBugLoad(id));
  if (!report) throw new Error(`Bug report ${id} was not found.`);
  const logcat = await webBugLoadLog(id).catch(() => "");
  const accessibility = await (await readBugFile(id, "accessibility.json")).text().catch(() => "null");
  downloadBlob(new Blob([JSON.stringify({ report, logcat, accessibility: JSON.parse(accessibility) }, null, 2)], { type: "application/json" }), `${id}.andy-bug.json`);
  const video = await readBugFile(id, "capture.webm").catch(() => undefined);
  if (video) downloadBlob(video, `${id}.webm`);
  return json({ ok: true, files: video ? 2 : 1 });
}

function clearBugPlayback() {
  if (!state.bugPlayback) return;
  state.bugPlayback.video.pause();
  state.bugPlayback.video.remove();
  URL.revokeObjectURL(state.bugPlayback.url);
  state.bugPlayback = undefined;
}

function attachBugPlayback(host) {
  const playback = state.bugPlayback;
  if (!playback) return;
  if (playback.video.parentElement !== host) host.replaceChildren(playback.video);
}

export async function webBugBeginPlayback(id, positionMillis, play) {
  if (state.bugPlayback?.id !== id) {
    clearBugPlayback();
    const file = await readBugFile(id, "capture.webm");
    const url = URL.createObjectURL(file);
    const video = document.createElement("video");
    video.src = url;
    video.muted = true;
    video.playsInline = true;
    video.preload = "auto";
    video.style.width = "100%";
    video.style.height = "100%";
    video.style.objectFit = "contain";
    video.style.background = "#000";
    state.bugPlayback = { id, video, url };
  }
  const video = state.bugPlayback.video;
  video.currentTime = Math.max(0, positionMillis / 1000);
  if (play) await video.play().catch(() => {}); else video.pause();
  return true;
}
