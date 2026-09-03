import { ExtensionMessage, WebviewMessage } from "../shared/protocol";
import { AudioLensApp } from "./app";
import { injectStyles } from "./styles";
import { renderShell } from "./view";

declare global {
  interface Window {
    __audioLensHostSend?: (message: string) => void;
    __audioLensReceive?: (message: HostExtensionMessage | string) => Promise<void>;
  }
}

type HostExtensionMessage = ExtensionMessage | {
  type: "chunk";
  requestId: number;
  offset: number;
  total: number;
  bytesUrl: string;
} | {
  type: "streamedAudioPeaks";
  requestId: number;
  minUrl: string;
  maxUrl: string;
} | {
  type: "streamedAudioSamples" | "streamedAudioWindows";
  requestId: number;
  samplesUrl: string;
  frameCount?: number;
  windowSize?: number;
};

const root = document.querySelector<HTMLDivElement>("#app");
if (!root) {
  throw new Error("AudioLens root element missing");
}

injectStyles();

let savedState: unknown;
const hostApi = {
  postMessage(message: WebviewMessage): void {
    if (!window.__audioLensHostSend) {
      throw new Error("AudioLens host bridge is unavailable");
    }
    window.__audioLensHostSend(JSON.stringify(message));
  },
  getState(): unknown {
    return savedState;
  },
  setState(state: unknown): void {
    savedState = state;
  }
};

async function fetchBytes(url: string): Promise<ArrayBuffer> {
  const response = await fetch(url, { cache: "no-store", credentials: "omit" });
  if (!response.ok) {
    throw new Error(`AudioLens data request failed (${response.status})`);
  }
  return response.arrayBuffer();
}

try {
  const app = new AudioLensApp(hostApi, renderShell(root));

  window.__audioLensReceive = async (wireMessage: HostExtensionMessage | string): Promise<void> => {
    const message = typeof wireMessage === "string" ? JSON.parse(wireMessage) as HostExtensionMessage : wireMessage;
    try {
      if ("bytesUrl" in message) {
        await app.handleMessage({ ...message, bytes: await fetchBytes(message.bytesUrl) });
      } else if ("minUrl" in message) {
        const [min, max] = await Promise.all([fetchBytes(message.minUrl), fetchBytes(message.maxUrl)]);
        await app.handleMessage({ ...message, min, max });
      } else if ("samplesUrl" in message) {
        await app.handleMessage({ ...message, samples: await fetchBytes(message.samplesUrl) } as ExtensionMessage);
      } else {
        await app.handleMessage(message);
      }
    } catch (error) {
      const detail = error instanceof Error ? error.message : String(error);
      if (message.type === "chunk") {
        await app.handleMessage({ type: "chunkError", requestId: message.requestId, message: detail });
      } else if ("requestId" in message) {
        await app.handleMessage({ type: "streamedAudioError", requestId: message.requestId, message: detail });
      } else {
        await app.handleMessage({ type: "error", message: detail });
      }
    }
  };

  hostApi.postMessage({ type: "ready" });
} catch (error) {
  const message = error instanceof Error ? error.message : String(error);
  root.textContent = `AudioLens initialization failed: ${message}`;
  hostApi.postMessage({ type: "showError", message: `AudioLens initialization failed: ${message}` });
}
