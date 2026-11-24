import { useCallback, useEffect, useRef, useState } from "react";
import { UploadCloud, RefreshCw, Plus, Eye, Trash2, X, ShieldAlert, Loader2 } from "lucide-react";
import { DocumentItem, MeInfo, NamespaceStat, apiRequest } from "@/lib/api";

const PAGE_SIZE = 15;

interface LibraryViewProps {
  apiKey: string;
  me: MeInfo;
  namespaces: NamespaceStat[];
  onRefreshNamespaces: () => void;
  onUnauthorized: () => void;
  toast: (msg: string, isError?: boolean) => void;
}

export function LibraryView({
  apiKey,
  me,
  namespaces,
  onRefreshNamespaces,
  onUnauthorized,
  toast,
}: LibraryViewProps) {
  const [docs, setDocs] = useState<DocumentItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [hasMore, setHasMore] = useState(true);
  const [nsFilter, setNsFilter] = useState("");
  const [keyPrefix, setKeyPrefix] = useState("ltc/");
  const [uploadingHint, setUploadingHint] = useState<string | null>(null);
  const [uploadErrors, setUploadErrors] = useState<{ name: string; message: string }[]>([]);
  const [dragging, setDragging] = useState(false);

  // 新建命名空间（仅 ADMIN）
  const [newNsName, setNewNsName] = useState("");
  const [newNsDesc, setNewNsDesc] = useState("");
  const [showNewNs, setShowNewNs] = useState(false);

  // 文档查看/编辑弹窗
  const [modalDoc, setModalDoc] = useState<any | null>(null);
  const [editContent, setEditContent] = useState("");
  const [savingDoc, setSavingDoc] = useState(false);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const sentinelRef = useRef<HTMLDivElement>(null);
  const offsetRef = useRef(0);
  const fetchingRef = useRef(false);

  const loadDocs = useCallback(
    async (reset = true, filterVal = nsFilter) => {
      if (fetchingRef.current) return;
      fetchingRef.current = true;
      const nextOffset = reset ? 0 : offsetRef.current;
      if (reset) {
        setLoading(true);
      } else {
        setLoadingMore(true);
      }
      try {
        const qs = filterVal ? `&namespace=${encodeURIComponent(filterVal)}` : "";
        const list = await apiRequest<DocumentItem[]>(
          `/v1/documents?limit=${PAGE_SIZE}&offset=${nextOffset}${qs}`,
          apiKey,
          { onUnauthorized }
        );
        if (reset) {
          setDocs(list);
          offsetRef.current = list.length;
        } else {
          setDocs((prev) => {
            const seen = new Set(prev.map((d) => d.id));
            const appended = list.filter((d) => !seen.has(d.id));
            return [...prev, ...appended];
          });
          offsetRef.current = nextOffset + list.length;
        }
        setHasMore(list.length === PAGE_SIZE);
      } catch (e: any) {
        toast("加载文档失败：" + e.message, true);
      } finally {
        setLoading(false);
        setLoadingMore(false);
        fetchingRef.current = false;
      }
    },
    [apiKey, nsFilter, onUnauthorized, toast]
  );

  useEffect(() => {
    setHasMore(true);
    offsetRef.current = 0;
    loadDocs(true, nsFilter);
  }, [nsFilter, loadDocs]);

  // 滚动到底部自动加载下一页（IntersectionObserver）
  useEffect(() => {
    const el = sentinelRef.current;
    if (!el || !hasMore) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting && !fetchingRef.current && hasMore) {
          loadDocs(false, nsFilter);
        }
      },
      { rootMargin: "120px" }
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [hasMore, nsFilter, loadDocs, docs.length]);

  const pollIngestJob = async (jobId: string) => {
    for (let i = 0; i < 180; i++) {
      await new Promise((r) => setTimeout(r, 1500));
      const job = await apiRequest<any>(`/v1/documents/jobs/${encodeURIComponent(jobId)}`, apiKey, {
        onUnauthorized,
      });
      if (job.status === "SUCCEEDED") return job.result;
      if (job.status === "FAILED") throw new Error(job.error || "后台入库失败");
    }
    throw new Error("入库轮询超时，请稍后刷新文档库确认");
  };

  const handleUploadFiles = async (files: FileList | null) => {
    if (!me.admin || !files || !files.length) return;
    setUploadErrors([]);
    let ok = 0;
    let skipped = 0;
    const errs: { name: string; message: string }[] = [];

    for (const file of Array.from(files)) {
      const base = file.name.replace(/\.[^.]+$/, "");
      const form = new FormData();
      form.append("file", file, file.name);
      form.append("docKey", keyPrefix.trim() + base);
      form.append("title", base);
      if (nsFilter) form.append("namespace", nsFilter);

      const useAsync = file.size > 3 * 1024 * 1024 || /\.(pdf|docx)$/i.test(file.name);
      setUploadingHint(
        useAsync
          ? `后台异步解析中：《${file.name}》（支持文档内嵌图片自动 OCR）…`
          : `正在入库：《${file.name}》…`
      );
      try {
        let r = await apiRequest<any>(`/v1/documents${useAsync ? "?async=true" : ""}`, apiKey, {
          method: "POST",
          form,
          onUnauthorized,
        });
        if (useAsync && r.jobId) {
          r = await pollIngestJob(r.jobId);
        }
        if (r.changed) {
          ok++;
          const reuseNote =
            r.reusedVectors > 0 ? `（复用 ${r.reusedVectors} / 新算 ${r.embeddedVectors}）` : "";
          toast(`《${file.name}》入库完成，${r.document.chunkCount} 个分块${reuseNote}`);
        } else {
          skipped++;
          toast(`《${file.name}》内容未变，已跳过`);
        }
      } catch (e: any) {
        errs.push({ name: file.name, message: e.message || "未知错误" });
      }
    }

    setUploadingHint(null);
    loadDocs();
    onRefreshNamespaces();

    if (errs.length > 0) {
      setUploadErrors(errs);
      const summary =
        errs.length === 1 && ok === 0 && skipped === 0
          ? `《${errs[0].name}》失败：${errs[0].message}`
          : `完成 ${ok} 个，跳过 ${skipped} 个，失败 ${errs.length} 个`;
      toast(summary, true);
    } else if (files.length > 1) {
      toast(`本次完成 ${ok} 个，跳过 ${skipped} 个`);
    }
  };

  const createNamespace = async () => {
    const name = newNsName.trim();
    if (!name) return toast("请输入命名空间名称", true);
    try {
      await apiRequest("/v1/namespaces", apiKey, {
        method: "POST",
        body: { name, description: newNsDesc.trim() },
        onUnauthorized,
      });
      setNewNsName("");
      setNewNsDesc("");
      setShowNewNs(false);
      onRefreshNamespaces();
      toast(`命名空间 ${name} 已就绪`);
    } catch (e: any) {
      toast("创建失败：" + e.message, true);
    }
  };

  const openDoc = async (id: number) => {
    try {
      const d = await apiRequest<any>(`/v1/documents/${id}/content`, apiKey, { onUnauthorized });
      setModalDoc(d);
      setEditContent(d.content ?? "");
    } catch (e: any) {
      toast("读取正文失败：" + e.message, true);
    }
  };

  const saveDoc = async () => {
    if (!modalDoc || !editContent.trim()) return;
    setSavingDoc(true);
    try {
      const r = await apiRequest<any>(`/v1/documents/${modalDoc.id}/content`, apiKey, {
        method: "PUT",
        body: { content: editContent },
        onUnauthorized,
      });
      toast(
        r.changed
          ? `已重建索引：v${r.document.version}（复用 ${r.reusedVectors} / 新算 ${r.embeddedVectors}）`
          : "正文未变，未重建"
      );
      setModalDoc(null);
      loadDocs();
      onRefreshNamespaces();
    } catch (e: any) {
      toast("保存失败：" + e.message, true);
    } finally {
      setSavingDoc(false);
    }
  };

  const deleteDoc = async (doc: DocumentItem) => {
    if (!confirm(`确定删除《${doc.title}》及其全部向量？`)) return;
    try {
      await apiRequest(`/v1/documents/${doc.id}`, apiKey, {
        method: "DELETE",
        onUnauthorized,
      });
      toast("已删除");
      loadDocs();
      onRefreshNamespaces();
    } catch (e: any) {
      toast("删除失败：" + e.message, true);
    }
  };

  return (
    <div className="space-y-5 pb-12">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h2 className="text-xl font-semibold tracking-tight">文档库</h2>
          <p className="text-xs text-[#8b98a5] mt-1">
            {me.admin
              ? "同一 docKey 重复上传视为版本更新，未变分块自动原位复用向量。"
              : `只读视图：当前角色仅可查看授权命名空间${
                  me.allowedNamespaces?.length ? `（${me.allowedNamespaces.join(", ")}）` : ""
                }下的文档。`}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <select
            value={nsFilter}
            onChange={(e) => setNsFilter(e.target.value)}
            className="rounded-lg border border-[#22303f] bg-[#111821] px-2.5 py-1.5 text-xs text-[#e6edf3] outline-none focus:border-[#3fd0c9]"
          >
            <option value="">全部可见命名空间</option>
            {namespaces.map((ns) => (
              <option key={ns.namespace} value={ns.namespace}>
                {ns.namespace}（{ns.docs} 篇）
              </option>
            ))}
          </select>
          {me.admin && (
            <>
              <input
                value={keyPrefix}
                onChange={(e) => setKeyPrefix(e.target.value)}
                placeholder="docKey 前缀"
                className="w-28 rounded-lg border border-[#22303f] bg-[#111821] px-2.5 py-1.5 text-xs text-[#e6edf3] outline-none focus:border-[#3fd0c9]"
              />
              <button
                type="button"
                onClick={() => setShowNewNs((v) => !v)}
                className="inline-flex items-center gap-1 rounded-lg border border-[#22303f] px-2.5 py-1.5 text-xs text-[#e6edf3] hover:border-[#3fd0c9] hover:text-[#3fd0c9] transition cursor-pointer"
              >
                <Plus className="h-3.5 w-3.5" />
                新建命名空间
              </button>
            </>
          )}
          <button
            type="button"
            onClick={() => loadDocs()}
            className="inline-flex items-center gap-1 rounded-lg border border-[#22303f] px-2.5 py-1.5 text-xs text-[#8b98a5] hover:border-[#3fd0c9] hover:text-[#3fd0c9] transition cursor-pointer"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${loading ? "animate-spin" : ""}`} />
            刷新
          </button>
        </div>
      </div>

      {/* 新建命名空间表单 */}
      {me.admin && showNewNs && (
        <div className="rounded-xl border border-[#22303f] bg-[#111821] p-4 flex flex-wrap items-center gap-2.5">
          <input
            value={newNsName}
            onChange={(e) => setNewNsName(e.target.value)}
            placeholder="命名空间名称（小写字母/数字/-/_，如 hr、finance）"
            className="flex-1 min-w-[200px] rounded-lg border border-[#22303f] bg-[#0b0f14] px-3 py-1.5 text-xs outline-none focus:border-[#3fd0c9]"
          />
          <input
            value={newNsDesc}
            onChange={(e) => setNewNsDesc(e.target.value)}
            placeholder="说明（可选）"
            className="flex-1 min-w-[180px] rounded-lg border border-[#22303f] bg-[#0b0f14] px-3 py-1.5 text-xs outline-none focus:border-[#3fd0c9]"
          />
          <button
            type="button"
            onClick={createNamespace}
            className="rounded-lg bg-[#3fd0c9] px-3.5 py-1.5 text-xs font-semibold text-[#06221f] cursor-pointer"
          >
            创建
          </button>
        </div>
      )}

      {/* 拖拽上传区（仅 ADMIN 可见） */}
      {me.admin && (
        <div
          onClick={() => fileInputRef.current?.click()}
          onDragOver={(e) => {
            e.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={(e) => {
            e.preventDefault();
            setDragging(false);
            handleUploadFiles(e.dataTransfer.files);
          }}
          className={`rounded-xl border-2 border-dashed p-7 text-center transition cursor-pointer ${
            dragging
              ? "border-[#3fd0c9] bg-[#3fd0c9]/10 text-[#3fd0c9]"
              : "border-[#22303f] bg-[#111821]/60 text-[#8b98a5] hover:border-[#3fd0c9]/60"
          }`}
        >
          <UploadCloud className="mx-auto h-7 w-7 mb-2 text-[#3fd0c9]" />
          <div className="text-sm font-medium text-[#e6edf3]">
            {uploadingHint || "拖拽文件到此处，或点击选择上传"}
          </div>
          <div className="text-xs mt-1">
            支持 md / txt / docx / xlsx / xls / pdf / html / png / jpg（≤ 40MB）；Word/PDF 内嵌图片与截图会自动 OCR
          </div>
          <input
            ref={fileInputRef}
            type="file"
            multiple
            className="hidden"
            accept=".md,.markdown,.txt,.docx,.xlsx,.xls,.pdf,.html,.htm,.png,.jpg,.jpeg,.bmp,.webp,.tif,.tiff"
            onChange={(e) => {
              handleUploadFiles(e.target.files);
              e.target.value = "";
            }}
          />
        </div>
      )}

      {/* 醒目上传失败详情卡片 */}
      {uploadErrors.length > 0 && (
        <div className="rounded-xl border border-[#ff6b6b]/50 bg-[#ff6b6b]/10 p-4">
          <div className="flex items-center justify-between mb-1.5">
            <span className="inline-flex items-center gap-1.5 text-xs font-semibold text-[#ff6b6b]">
              <ShieldAlert className="h-4 w-4" />
              上传失败详情
            </span>
            <button
              type="button"
              onClick={() => setUploadErrors([])}
              className="text-xs text-[#8b98a5] hover:text-white underline cursor-pointer"
            >
              关闭
            </button>
          </div>
          <div className="space-y-1 text-xs text-[#ffc9c9]">
            {uploadErrors.map((err, i) => (
              <div key={i}>
                • <strong>{err.name}</strong>：{err.message}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 文档列表表格 */}
      <div className="overflow-x-auto rounded-xl border border-[#22303f] bg-[#111821]">
        <table className="w-full text-left text-xs border-collapse">
          <thead>
            <tr className="border-b border-[#22303f] bg-[#16202c] text-[#8b98a5]">
              <th className="px-4 py-3 font-medium">标题</th>
              <th className="px-4 py-3 font-medium">命名空间</th>
              <th className="px-4 py-3 font-medium">格式</th>
              <th className="px-4 py-3 font-medium">分块</th>
              <th className="px-4 py-3 font-medium">版本</th>
              <th className="px-4 py-3 font-medium">更新时间</th>
              <th className="px-4 py-3 font-medium text-right">操作</th>
            </tr>
          </thead>
          <tbody>
            {docs.length === 0 ? (
              <tr>
                <td colSpan={7} className="px-4 py-10 text-center text-[#8b98a5]">
                  {loading ? "加载中…" : "当前命名空间下暂无文档"}
                </td>
              </tr>
            ) : (
              docs.map((d) => (
                <tr key={d.id} className="border-b border-[#22303f]/60 hover:bg-[#7aa2ff]/5">
                  <td className="px-4 py-2.5 font-medium text-[#e6edf3]" title={d.docKey}>
                    {d.title}
                  </td>
                  <td className="px-4 py-2.5">
                    <span className="rounded-full border border-[#22303f] bg-[#16202c] px-2 py-0.5 text-[11px] text-[#8b98a5]">
                      {d.namespace}
                    </span>
                  </td>
                  <td className="px-4 py-2.5 text-[#8b98a5]">{d.format}</td>
                  <td className="px-4 py-2.5">{d.chunkCount}</td>
                  <td className="px-4 py-2.5 text-[#8b98a5]">v{d.version}</td>
                  <td className="px-4 py-2.5 text-[#8b98a5]">
                    {(d.updatedAt || "").replace("T", " ").slice(0, 19)}
                  </td>
                  <td className="px-4 py-2.5 text-right">
                    <div className="inline-flex items-center gap-1.5">
                      <button
                        type="button"
                        onClick={() => openDoc(d.id)}
                        className="inline-flex items-center gap-1 rounded-md border border-[#22303f] px-2.5 py-1 text-[11.5px] text-[#e6edf3] hover:border-[#3fd0c9] hover:text-[#3fd0c9] transition cursor-pointer"
                      >
                        <Eye className="h-3 w-3" />
                        {me.admin ? "查看/编辑" : "查看"}
                      </button>
                      {me.admin && (
                        <button
                          type="button"
                          onClick={() => deleteDoc(d)}
                          className="inline-flex items-center gap-1 rounded-md border border-[#ff6b6b]/35 px-2.5 py-1 text-[11.5px] text-[#ff6b6b] hover:bg-[#ff6b6b]/10 transition cursor-pointer"
                        >
                          <Trash2 className="h-3 w-3" />
                          删除
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* 滚动翻页触底哨兵与加载状态栏 */}
      <div
        ref={sentinelRef}
        className="flex items-center justify-center py-4 text-xs text-[#8b98a5]"
      >
        {loadingMore ? (
          <span className="inline-flex items-center gap-2">
            <Loader2 className="h-3.5 w-3.5 animate-spin text-[#3fd0c9]" />
            正在加载更多文档…
          </span>
        ) : hasMore && docs.length > 0 ? (
          <button
            type="button"
            onClick={() => loadDocs(false)}
            className="rounded-lg border border-[#22303f] px-3.5 py-1.5 text-xs text-[#8b98a5] hover:border-[#3fd0c9] hover:text-[#3fd0c9] transition cursor-pointer"
          >
            加载更多（已显示 {docs.length} 篇）
          </button>
        ) : docs.length > 0 ? (
          <span>— 已加载全部 {docs.length} 篇文档 —</span>
        ) : null}
      </div>

      {/* 文档查看/编辑弹窗 */}
      {modalDoc && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4"
          onClick={() => setModalDoc(null)}
        >
          <div
            className="flex max-h-[88vh] w-full max-w-4xl flex-col overflow-hidden rounded-2xl border border-[#22303f] bg-[#111821] shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between border-b border-[#22303f] px-5 py-3.5">
              <div className="min-w-0">
                <h3 className="text-sm font-semibold truncate">{modalDoc.title}</h3>
                <div className="text-[11.5px] text-[#8b98a5]">
                  {modalDoc.namespace} · {modalDoc.format} · v{modalDoc.version} · {modalDoc.chunkCount} 块 · {modalDoc.charCount} 字
                </div>
              </div>
              <button
                type="button"
                onClick={() => setModalDoc(null)}
                className="rounded-lg p-1.5 text-[#8b98a5] hover:bg-[#16202c] hover:text-white cursor-pointer"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
            <div className="flex-1 overflow-auto p-5">
              <p className="text-xs text-[#8b98a5] mb-2.5">
                {me.admin
                  ? "正文为规范化 Markdown，修改保存后会增量复用未变分块向量并重建索引（版本号 +1）。"
                  : "只读模式：当前角色仅可查阅正文，无修改权限。"}
              </p>
              <textarea
                value={editContent}
                onChange={(e) => setEditContent(e.target.value)}
                disabled={!me.admin || modalDoc.content == null}
                spellCheck={false}
                className="h-[52vh] w-full resize-y rounded-xl border border-[#22303f] bg-[#0b0f14] p-3.5 font-mono text-xs leading-relaxed text-[#e6edf3] outline-none focus:border-[#3fd0c9] disabled:opacity-75"
              />
            </div>
            <div className="flex items-center justify-between border-t border-[#22303f] px-5 py-3.5">
              <span className="text-xs text-[#8b98a5]">{editContent.length} 字符</span>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => setModalDoc(null)}
                  className="rounded-lg border border-[#22303f] px-3.5 py-1.5 text-xs text-[#e6edf3] hover:border-[#3fd0c9] cursor-pointer"
                >
                  关闭
                </button>
                {me.admin && modalDoc.content != null && (
                  <button
                    type="button"
                    disabled={savingDoc}
                    onClick={saveDoc}
                    className="rounded-lg bg-[#3fd0c9] px-4 py-1.5 text-xs font-semibold text-[#06221f] hover:brightness-110 disabled:opacity-50 cursor-pointer"
                  >
                    {savingDoc ? "重建中…" : "保存并增量重建"}
                  </button>
                )}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
