import { useEffect, useRef, useState } from "react";
import { Send, Trash2, BookOpen, ExternalLink } from "lucide-react";
import {
  CitationItem,
  MeInfo,
  NamespaceStat,
  QaStats,
  parseSseFrame,
  renderMarkdown,
} from "@/lib/api";

interface ChatMessage {
  id: string;
  role: "user" | "ai";
  text: string;
  rendered: boolean;
  citations?: CitationItem[];
  stats?: QaStats;
  grounded?: boolean;
}

interface ChatViewProps {
  apiKey: string;
  me: MeInfo;
  namespaces: NamespaceStat[];
  selectedNs: string;
  onSelectNs: (ns: string) => void;
  toast: (msg: string, isError?: boolean) => void;
}

export function ChatView({
  apiKey,
  me,
  namespaces,
  selectedNs,
  onSelectNs,
  toast,
}: ChatViewProps) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [question, setQuestion] = useState("");
  const [topK, setTopK] = useState(6);
  const [streaming, setStreaming] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  const ask = async () => {
    const q = question.trim();
    if (!q || streaming) return;
    setQuestion("");
    setStreaming(true);

    const userMsg: ChatMessage = {
      id: "u-" + Date.now(),
      role: "user",
      text: q,
      rendered: false,
    };
    const aiId = "a-" + Date.now();
    const aiMsg: ChatMessage = {
      id: aiId,
      role: "ai",
      text: "正在检索文档…",
      rendered: false,
    };
    setMessages((prev) => [...prev, userMsg, aiMsg]);

    let full = "";
    let started = false;

    try {
      const targetNs = selectedNs ? [selectedNs] : ["*"];
      const res = await fetch("/v1/qa/stream", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-API-Key": apiKey },
        body: JSON.stringify({ question: q, namespaces: targetNs, topK }),
      });
      if (!res.ok) {
        const errText = await res.text();
        let msg = errText || `HTTP ${res.status}`;
        try {
          const parsed = JSON.parse(errText);
          if (parsed?.message) msg = parsed.message;
        } catch {
          /* ignore */
        }
        throw new Error(msg);
      }

      const reader = res.body!.getReader();
      const decoder = new TextDecoder();
      let buffer = "";

      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });

        let idx: number;
        while ((idx = buffer.indexOf("\n\n")) >= 0) {
          const frame = buffer.slice(0, idx);
          buffer = buffer.slice(idx + 2);
          const handled = parseSseFrame(frame);
          if (!handled) continue;
          if (handled.type === "delta") {
            if (!started) started = true;
            full += handled.content;
            const snapshot = full;
            setMessages((prev) =>
              prev.map((m) => (m.id === aiId ? { ...m, text: snapshot } : m))
            );
          } else if (handled.type === "citations") {
            const snapshot = full;
            setMessages((prev) =>
              prev.map((m) =>
                m.id === aiId
                  ? {
                      ...m,
                      text: snapshot,
                      rendered: true,
                      citations: handled.citations,
                      stats: handled.stats,
                      grounded: handled.grounded,
                    }
                  : m
              )
            );
          } else if (handled.type === "error") {
            toast(handled.message, true);
          }
        }
      }
      if (!full) {
        setMessages((prev) =>
          prev.map((m) => (m.id === aiId ? { ...m, text: "（无内容）" } : m))
        );
      }
    } catch (e: any) {
      const errMsg = e?.message || "请求失败";
      setMessages((prev) =>
        prev.map((m) => (m.id === aiId ? { ...m, text: "请求失败：" + errMsg } : m))
      );
      toast("问答失败：" + errMsg, true);
    } finally {
      setStreaming(false);
    }
  };

  const handleCitationClick = (msgId: string, citeNum: string) => {
    const details = document.getElementById(`cites-${msgId}`) as HTMLDetailsElement | null;
    if (details) details.open = true;
    const card = document.getElementById(`cite-${msgId}-${citeNum}`);
    if (card) {
      card.scrollIntoView({ behavior: "smooth", block: "center" });
      card.classList.add("cite-flash");
      setTimeout(() => card.classList.remove("cite-flash"), 1200);
    }
  };

  return (
    <div className="flex flex-col h-full">
      <div className="flex flex-wrap items-end justify-between gap-4 mb-5">
        <div>
          <h2 className="text-xl font-semibold tracking-tight">知识问答</h2>
          <p className="text-xs text-[#8b98a5] mt-1">
            答案只依据授权知识库文档生成，每条结论自带结构化引用，点击正文 <code>[n]</code> 可直达原文片段。
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <span className="rounded-full border border-[#22303f] bg-[#16202c] px-2.5 py-1 text-xs text-[#8b98a5]">
            模型: {me.chatModel || "—"}
          </span>
          <select
            value={selectedNs}
            onChange={(e) => onSelectNs(e.target.value)}
            className="rounded-lg border border-[#22303f] bg-[#111821] px-2.5 py-1.5 text-xs text-[#e6edf3] outline-none focus:border-[#3fd0c9]"
          >
            <option value="">
              {me.allowedNamespaces?.length
                ? `全部已授权库 (${me.allowedNamespaces.join(", ")})`
                : "全部知识库"}
            </option>
            {namespaces.map((ns) => (
              <option key={ns.namespace} value={ns.namespace}>
                {ns.namespace}（{ns.docs} 篇）
              </option>
            ))}
          </select>
          <select
            value={topK}
            onChange={(e) => setTopK(Number(e.target.value))}
            className="rounded-lg border border-[#22303f] bg-[#111821] px-2.5 py-1.5 text-xs text-[#e6edf3] outline-none focus:border-[#3fd0c9]"
          >
            <option value={4}>引用 4 条</option>
            <option value={6}>引用 6 条</option>
            <option value={8}>引用 8 条</option>
          </select>
          <button
            type="button"
            onClick={() => setMessages([])}
            className="inline-flex items-center gap-1 rounded-lg border border-[#22303f] px-2.5 py-1.5 text-xs text-[#8b98a5] hover:border-[#3fd0c9] hover:text-[#3fd0c9] transition cursor-pointer"
          >
            <Trash2 className="h-3.5 w-3.5" />
            清空
          </button>
        </div>
      </div>

      {/* 聊天消息列表 */}
      <div className="flex-1 space-y-5 pb-32">
        {messages.length === 0 && (
          <div className="rounded-2xl border border-dashed border-[#22303f] bg-[#111821]/50 p-10 text-center">
            <BookOpen className="mx-auto h-8 w-8 text-[#3fd0c9]/70 mb-3" />
            <p className="text-sm text-[#e6edf3] font-medium">向 LTC 知识库提问</p>
            <p className="text-xs text-[#8b98a5] mt-1">
              例如：「日报用哪张表？」、「线索转商机有哪些撞单判定规则？」、「合同审批流程是什么？」
            </p>
          </div>
        )}

        {messages.map((m) => (
          <div
            key={m.id}
            className={`flex items-start gap-3 ${m.role === "user" ? "flex-row-reverse" : ""}`}
          >
            <div
              className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border text-xs font-medium ${
                m.role === "user"
                  ? "border-[#7aa2ff]/30 bg-[#7aa2ff]/15 text-[#7aa2ff]"
                  : "border-[#22303f] bg-[#16202c] text-[#3fd0c9]"
              }`}
            >
              {m.role === "user" ? "我" : "AI"}
            </div>

            <div
              className={`flex flex-col rounded-xl border px-4 py-3.5 ${
                m.role === "user"
                  ? "max-w-[78%] border-[#7aa2ff]/25 bg-[#7aa2ff]/10 text-sm whitespace-pre-wrap break-words"
                  : "flex-1 min-w-0 border-[#22303f] bg-[#111821] text-sm"
              }`}
            >
              {m.rendered ? (
                <div
                  className="md-body leading-relaxed break-words"
                  dangerouslySetInnerHTML={{ __html: renderMarkdown(m.text) }}
                  onClick={(e) => {
                    const target = e.target as HTMLElement;
                    if (target.classList.contains("cin") && target.dataset.n) {
                      handleCitationClick(m.id, target.dataset.n);
                    }
                  }}
                />
              ) : (
                <div className="whitespace-pre-wrap break-words leading-relaxed">{m.text}</div>
              )}

              {/* 统计信息行：严格位于正文下方 */}
              {(m.stats || m.grounded === false) && (
                <div className="mt-3 flex flex-wrap items-center gap-3.5 border-t border-dashed border-[#22303f] pt-2.5 text-[11.5px] text-[#8b98a5]">
                  {m.stats && (
                    <>
                      <span>检索 {m.stats.retrievalMs}ms</span>
                      <span>生成 {m.stats.llmMs}ms</span>
                      <span>引用 {m.citations?.length ?? 0} 条</span>
                      {m.stats.promptTokens != null && (
                        <span>
                          tokens {m.stats.promptTokens}/{m.stats.completionTokens ?? 0}
                        </span>
                      )}
                    </>
                  )}
                  {m.grounded === false && (
                    <span className="rounded-full border border-[#f5c451]/40 bg-[#f5c451]/10 px-2 py-0.5 text-[11px] text-[#f5c451]">
                      未检索到依据
                    </span>
                  )}
                </div>
              )}

              {/* 参考资料折叠区：严格位于正文正下方 */}
              {m.citations && m.citations.length > 0 && (
                <details
                  id={`cites-${m.id}`}
                  className="mt-3 border-t border-dashed border-[#22303f] pt-2.5"
                >
                  <summary className="cursor-pointer select-none text-xs text-[#8b98a5] hover:text-[#3fd0c9]">
                    参考资料 {m.citations.length} 条
                  </summary>
                  <div className="mt-2.5 flex flex-col gap-2">
                    {m.citations.map((c) => (
                      <div
                        key={c.index}
                        id={`cite-${m.id}-${c.index}`}
                        className="rounded-lg border border-[#22303f] border-l-[3px] border-l-[#3fd0c9] bg-[#16202c] p-3 transition"
                      >
                        <div className="flex items-center gap-2 text-xs">
                          <span className="rounded bg-[#3fd0c9]/15 px-1.5 py-0.5 font-semibold text-[#3fd0c9]">
                            {c.index}
                          </span>
                          <span className="font-semibold text-[#e6edf3] truncate flex-1">
                            {c.title}
                          </span>
                          {c.page != null && (
                            <span className="rounded-full border border-[#22303f] px-2 py-0.5 text-[11px] text-[#8b98a5]">
                              第 {c.page} 页
                            </span>
                          )}
                          <span className="text-[11px] text-[#8b98a5]">
                            {Number(c.score || 0).toFixed(3)}
                          </span>
                        </div>
                        {c.headingPath && (
                          <div className="mt-1 text-[11.5px] text-[#8b98a5]">{c.headingPath}</div>
                        )}
                        <div className="mt-1 text-xs text-[#b7c2cd] line-clamp-3 leading-relaxed">
                          {c.snippet}
                        </div>
                        {c.url && (
                          <a
                            href={c.url}
                            target="_blank"
                            rel="noreferrer"
                            className="mt-1.5 inline-flex items-center gap-1 text-[11.5px] text-[#7aa2ff] hover:underline"
                          >
                            查看原文 <ExternalLink className="h-3 w-3" />
                          </a>
                        )}
                      </div>
                    ))}
                  </div>
                </details>
              )}
            </div>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>

      {/* 底部输入框 */}
      <div className="fixed bottom-0 left-0 md:left-[240px] right-0 z-10 bg-gradient-to-t from-[#0b0f14] via-[#0b0f14]/95 to-transparent px-4 md:px-8 pb-5 pt-6">
        <div className="mx-auto flex max-w-5xl items-end gap-2.5 rounded-2xl border border-[#22303f] bg-[#111821] p-2.5 shadow-2xl">
          <textarea
            rows={1}
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                ask();
              }
            }}
            placeholder="问点什么…（Enter 发送，Shift+Enter 换行）"
            className="max-h-40 min-h-[40px] flex-1 resize-none bg-transparent px-2 py-1.5 text-sm text-[#e6edf3] placeholder-[#6b7785] outline-none"
          />
          <button
            type="button"
            disabled={streaming || !question.trim()}
            onClick={ask}
            className="inline-flex h-10 items-center gap-1.5 rounded-xl bg-[#3fd0c9] px-4 text-sm font-semibold text-[#06221f] hover:brightness-110 disabled:opacity-50 transition cursor-pointer"
          >
            <Send className="h-4 w-4" />
            发送
          </button>
        </div>
      </div>
    </div>
  );
}
