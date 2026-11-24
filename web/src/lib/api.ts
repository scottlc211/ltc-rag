export interface MeInfo {
  keyId: number;
  name: string;
  role: string;
  admin: boolean;
  allowedNamespaces: string[];
  chatModel: string;
}

export interface NamespaceStat {
  namespace: string;
  description?: string;
  docs: number;
  chunks: number;
}

export interface DocumentItem {
  id: number;
  namespace: string;
  docKey: string;
  title: string;
  format: string;
  version: number;
  status: string;
  chunkCount: number;
  charCount: number;
  originUrl?: string | null;
  error?: string | null;
  updatedAt?: string;
}

export interface CitationItem {
  index: number;
  docId: number;
  chunkId: number;
  title: string;
  headingPath?: string;
  url?: string | null;
  score: number;
  snippet?: string;
  page?: number | null;
}

export interface QaStats {
  hits: number;
  retrievalMs: number;
  llmMs: number;
  promptTokens?: number | null;
  completionTokens?: number | null;
}

export interface KeySummary {
  id: number;
  name: string;
  keyPrefix: string;
  role: string;
  status: string;
  quotaPerDay?: number | null;
  allowedNamespaces: string[];
  createdAt?: string | null;
  lastUsedAt?: string | null;
}

export interface ChatConfigView {
  protocol: string;
  baseUrl: string;
  model: string;
  apiKeyMasked: string;
  configured: boolean;
}

export async function apiRequest<T = any>(
  path: string,
  key: string,
  options: { method?: string; body?: unknown; form?: FormData; onUnauthorized?: () => void } = {}
): Promise<T> {
  const { method = "GET", body, form, onUnauthorized } = options;
  const headers: Record<string, string> = { "X-API-Key": key };
  let payload: BodyInit | undefined;
  if (form) {
    payload = form;
  } else if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    payload = JSON.stringify(body);
  }
  const res = await fetch(path, { method, headers, body: payload });
  const text = await res.text();
  let data: any = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = { message: text };
    }
  }
  if (!res.ok) {
    if (res.status === 401 && onUnauthorized) {
      onUnauthorized();
    }
    const err = new Error((data && data.message) || `HTTP ${res.status}`) as Error & {
      code?: string;
      status?: number;
    };
    err.code = data && data.code;
    err.status = res.status;
    throw err;
  }
  return data as T;
}

export function escHtml(s: string): string {
  return String(s).replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c] || c)
  );
}

function inlineMd(text: string): string {
  return escHtml(text)
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/\[(\d+)\]/g, '<sup class="cin" data-n="$1">[$1]</sup>');
}

export function renderMarkdown(text: string): string {
  const codeBlocks: string[] = [];
  const src = String(text).replace(/```[^\n]*\n?([\s\S]*?)```/g, (_, code) => {
    codeBlocks.push(`<pre class="code"><code>${escHtml(code.replace(/\n$/, ""))}</code></pre>`);
    return `\u0000${codeBlocks.length - 1}\u0000`;
  });

  const out: string[] = [];
  let list: "ul" | "ol" | null = null;
  let para: string[] = [];
  const flushPara = () => {
    if (para.length) {
      out.push(`<p>${para.join("<br>")}</p>`);
      para = [];
    }
  };
  const closeList = () => {
    if (list) {
      out.push(`</${list}>`);
      list = null;
    }
  };

  for (const rawLine of src.split("\n")) {
    const line = rawLine.replace(/\s+$/, "");
    if (!line.trim()) {
      flushPara();
      closeList();
      continue;
    }
    const heading = line.match(/^(#{1,4})\s+(.*)$/);
    if (heading) {
      flushPara();
      closeList();
      const level = Math.min(heading[1].length + 2, 6);
      out.push(`<h${level}>${inlineMd(heading[2])}</h${level}>`);
      continue;
    }
    const bullet = line.match(/^\s*[-*]\s+(.*)$/);
    if (bullet) {
      flushPara();
      if (list !== "ul") {
        closeList();
        out.push("<ul>");
        list = "ul";
      }
      out.push(`<li>${inlineMd(bullet[1])}</li>`);
      continue;
    }
    const ordered = line.match(/^\s*\d+[.)]\s+(.*)$/);
    if (ordered) {
      flushPara();
      if (list !== "ol") {
        closeList();
        out.push("<ol>");
        list = "ol";
      }
      out.push(`<li>${inlineMd(ordered[1])}</li>`);
      continue;
    }
    if (list) closeList();
    para.push(inlineMd(line));
  }
  flushPara();
  closeList();

  return out.join("").replace(/\u0000(\d+)\u0000/g, (_, i) => codeBlocks[Number(i)]);
}

export function parseSseFrame(frame: string) {
  let event = "message";
  const dataLines: string[] = [];
  for (const raw of frame.split("\n")) {
    const line = raw.replace(/\r$/, "");
    if (line.startsWith("event:")) event = line.slice(6).trim();
    else if (line.startsWith("data:")) dataLines.push(line.slice(5).trim());
  }
  if (!dataLines.length) return null;
  let data: any;
  try {
    data = JSON.parse(dataLines.join("\n"));
  } catch {
    return null;
  }
  if (event === "delta") return { type: "delta" as const, content: String(data.content || "") };
  if (event === "citations")
    return {
      type: "citations" as const,
      citations: (data.citations || []) as CitationItem[],
      stats: data.stats as QaStats | undefined,
      grounded: Boolean(data.grounded),
    };
  if (event === "error") return { type: "error" as const, message: String(data.message || "流式处理失败") };
  return null;
}
