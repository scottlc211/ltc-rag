import { useCallback, useEffect, useState } from "react";
import {
  MessageSquare,
  Library,
  Clock,
  Settings,
  LogOut,
  Menu,
  X,
  Shield,
  Lock,
  Loader2,
} from "lucide-react";
import { ColdEmailCrowdHero } from "@/components/ui/cold-email-crowd-hero";
import { LoginModal } from "@/components/LoginModal";
import { ChatView } from "@/components/ChatView";
import { LibraryView } from "@/components/LibraryView";
import { TasksView } from "@/components/TasksView";
import { AdminView } from "@/components/AdminView";
import { MeInfo, NamespaceStat, apiRequest } from "@/lib/api";

type TabId = "chat" | "library" | "tasks" | "admin";

interface ToastState {
  msg: string;
  isError: boolean;
}

const STORAGE_KEY = "ltc.key";

export function App() {
  const [apiKey, setApiKey] = useState<string>(() => {
    // 支持通过 #k=ltc_xxx 快速登录，读取后立即清理 URL hash 避免泄露
    const m = window.location.hash.match(/k=([^&]+)/);
    if (m) {
      const k = decodeURIComponent(m[1]);
      localStorage.setItem(STORAGE_KEY, k);
      window.history.replaceState(null, "", window.location.pathname + window.location.search);
      return k;
    }
    return localStorage.getItem(STORAGE_KEY) || "";
  });

  const [me, setMe] = useState<MeInfo | null>(null);
  const [booting, setBooting] = useState<boolean>(() => Boolean(apiKey));
  const [loginOpen, setLoginOpen] = useState(false);
  const [activeTab, setActiveTab] = useState<TabId>("chat");
  const [namespaces, setNamespaces] = useState<NamespaceStat[]>([]);
  const [selectedNs, setSelectedNs] = useState<string>("");
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [toastState, setToastState] = useState<ToastState | null>(null);

  const toast = useCallback((msg: string, isError = false) => {
    setToastState({ msg, isError });
  }, []);

  useEffect(() => {
    if (!toastState) return;
    const t = setTimeout(() => setToastState(null), toastState.isError ? 4500 : 2800);
    return () => clearTimeout(t);
  }, [toastState]);

  const handleLogout = useCallback(() => {
    localStorage.removeItem(STORAGE_KEY);
    setApiKey("");
    setMe(null);
    setNamespaces([]);
    setSelectedNs("");
    setActiveTab("chat");
    setMobileMenuOpen(false);
  }, []);

  const loadNamespaces = useCallback(
    async (key: string) => {
      try {
        const list = await apiRequest<NamespaceStat[]>("/v1/namespaces", key, {
          onUnauthorized: handleLogout,
        });
        setNamespaces(list || []);
      } catch {
        // 静默忽略，避免干扰主流程
      }
    },
    [handleLogout]
  );

  const verifyKey = useCallback(
    async (key: string, fromModal = false) => {
      try {
        const info = await apiRequest<MeInfo>("/v1/me", key);
        localStorage.setItem(STORAGE_KEY, key);
        setApiKey(key);
        setMe(info);
        setLoginOpen(false);
        await loadNamespaces(key);
        if (fromModal) {
          toast(`欢迎回来，${info.name}（${info.role}）`);
        }
      } catch (e: any) {
        if (fromModal) {
          toast("Key 校验失败：" + (e.message || "无效或已禁用"), true);
        } else {
          localStorage.removeItem(STORAGE_KEY);
          setApiKey("");
          setMe(null);
        }
      } finally {
        setBooting(false);
      }
    },
    [loadNamespaces, toast]
  );

  // 启动时如果本地已有 Key，自动静默验证
  useEffect(() => {
    if (apiKey && !me) {
      verifyKey(apiKey, false);
    } else {
      setBooting(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 如果非管理员切到了 admin tab，自动回退到 chat
  useEffect(() => {
    if (me && !me.admin && activeTab === "admin") {
      setActiveTab("chat");
    }
  }, [me, activeTab]);

  // 初始校验中
  if (booting) {
    return (
      <div className="flex h-screen w-screen items-center justify-center bg-[#0b0f14] text-[#8b9bb0]">
        <div className="flex items-center gap-3 text-sm">
          <Loader2 className="h-5 w-5 animate-spin text-[#3fd0c9]" />
          <span>正在验证身份并加载知识库...</span>
        </div>
      </div>
    );
  }

  // 未登录：展示炫酷的 Crowd Hero 门户首页 + 登录弹窗
  if (!me) {
    return (
      <>
        <ColdEmailCrowdHero onLoginClick={() => setLoginOpen(true)} />
        <LoginModal
          open={loginOpen}
          onClose={() => setLoginOpen(false)}
          onSubmit={(k) => verifyKey(k, true)}
        />
        {toastState && (
          <div
            className={`fixed bottom-5 right-5 z-[60] max-w-md rounded-xl border px-4 py-2.5 text-sm shadow-xl ${
              toastState.isError
                ? "border-[#f85149]/50 bg-[#1a1114] text-[#ff7b72]"
                : "border-[#3fd0c9]/40 bg-[#111821] text-[#e6edf3]"
            }`}
          >
            {toastState.msg}
          </div>
        )}
      </>
    );
  }

  const navItems: { id: TabId; label: string; icon: typeof MessageSquare }[] = [
    { id: "chat", label: "知识问答", icon: MessageSquare },
    { id: "library", label: "知识库", icon: Library },
    { id: "tasks", label: "异步任务", icon: Clock },
    ...(me.admin ? [{ id: "admin" as TabId, label: "后台管理", icon: Settings }] : []),
  ];

  const isRestricted = Array.isArray(me.allowedNamespaces) && me.allowedNamespaces.length > 0;

  return (
    <div className="flex h-screen w-screen overflow-hidden bg-[#0b0f14] text-[#e6edf3]">
      {/* 移动端顶部栏 */}
      <div className="fixed top-0 left-0 right-0 z-30 flex h-13 items-center justify-between border-b border-[#22303f] bg-[#111821] px-4 md:hidden">
        <div className="flex items-center gap-2 font-semibold text-sm">
          <span className="h-2.5 w-2.5 rounded-full bg-[#3fd0c9] shadow-[0_0_10px_#3fd0c9]" />
          LTC 知识库
        </div>
        <button
          onClick={() => setMobileMenuOpen((v) => !v)}
          className="rounded-lg p-1.5 text-[#8b9bb0] hover:bg-[#17202b] hover:text-[#e6edf3]"
        >
          {mobileMenuOpen ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
        </button>
      </div>

      {/* 移动端遮罩 */}
      {mobileMenuOpen && (
        <div
          className="fixed inset-0 z-30 bg-black/60 md:hidden"
          onClick={() => setMobileMenuOpen(false)}
        />
      )}

      {/* 左侧导航栏 */}
      <aside
        className={`fixed inset-y-0 left-0 z-40 flex w-60 shrink-0 flex-col border-r border-[#22303f] bg-[#111821] transition-transform duration-200 md:static md:translate-x-0 ${
          mobileMenuOpen ? "translate-x-0" : "-translate-x-full"
        }`}
      >
        <div className="flex items-center gap-2.5 px-5 py-5">
          <span className="h-2.5 w-2.5 rounded-full bg-[#3fd0c9] shadow-[0_0_12px_#3fd0c9]" />
          <span className="text-[15px] font-semibold tracking-wide">LTC 知识库</span>
        </div>

        <nav className="flex-1 space-y-1 px-3">
          {navItems.map((item) => {
            const Icon = item.icon;
            const active = activeTab === item.id;
            return (
              <button
                key={item.id}
                onClick={() => {
                  setActiveTab(item.id);
                  setMobileMenuOpen(false);
                }}
                className={`flex w-full items-center gap-2.5 rounded-lg px-3 py-2.5 text-sm transition cursor-pointer ${
                  active
                    ? "bg-[#3fd0c9]/15 text-[#3fd0c9] font-medium"
                    : "text-[#8b9bb0] hover:bg-[#17202b] hover:text-[#e6edf3]"
                }`}
              >
                <Icon className="h-4 w-4 shrink-0" />
                <span>{item.label}</span>
              </button>
            );
          })}
        </nav>

        {/* 底部身份与权限状态区 */}
        <div className="border-t border-[#22303f] p-3.5 space-y-2.5 text-xs">
          <div className="rounded-lg border border-[#22303f] bg-[#0b0f14] p-2.5 space-y-1.5">
            <div className="flex items-center justify-between gap-2">
              <span className="truncate font-medium text-[#e6edf3]" title={me.name}>
                {me.name}
              </span>
              <span
                className={`inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-semibold ${
                  me.admin
                    ? "bg-[#3fd0c9]/15 text-[#3fd0c9]"
                    : "bg-[#d29922]/15 text-[#d29922]"
                }`}
              >
                <Shield className="h-2.5 w-2.5" />
                {me.role}
              </span>
            </div>

            <div className="flex items-center gap-1 text-[11px] text-[#8b9bb0]">
              <Lock className="h-3 w-3 shrink-0" />
              {isRestricted ? (
                <span className="truncate" title={me.allowedNamespaces.join(", ")}>
                  授权库：{me.allowedNamespaces.join(", ")}
                </span>
              ) : (
                <span>授权库：全部命名空间</span>
              )}
            </div>

            <div className="truncate font-mono text-[11px] text-[#6d7f94]" title={me.chatModel}>
              模型：{me.chatModel || "未配置"}
            </div>
          </div>

          <button
            onClick={handleLogout}
            className="flex w-full items-center justify-center gap-1.5 rounded-lg border border-[#22303f] bg-[#17202b] py-1.5 text-xs text-[#8b9bb0] hover:border-[#f85149]/50 hover:text-[#ff7b72] transition cursor-pointer"
          >
            <LogOut className="h-3.5 w-3.5" />
            退出登录 / 切换 Key
          </button>
        </div>
      </aside>

      {/* 右侧主内容区：独立纵向滚动 + 舒适内边距 */}
      <main className="flex-1 overflow-y-auto pt-13 md:pt-0">
        <div className="mx-auto w-full max-w-6xl px-5 py-6 md:px-8 md:py-7 min-h-full">
          {activeTab === "chat" && (
            <ChatView
              apiKey={apiKey}
              me={me}
              namespaces={namespaces}
              selectedNs={selectedNs}
              onSelectNs={setSelectedNs}
              toast={toast}
            />
          )}
          {activeTab === "library" && (
            <LibraryView
              apiKey={apiKey}
              me={me}
              namespaces={namespaces}
              onRefreshNamespaces={() => loadNamespaces(apiKey)}
              onUnauthorized={handleLogout}
              toast={toast}
            />
          )}
          {activeTab === "tasks" && (
            <TasksView
              apiKey={apiKey}
              selectedNs={selectedNs}
              onUnauthorized={handleLogout}
              toast={toast}
            />
          )}
          {activeTab === "admin" && me.admin && (
            <AdminView
              apiKey={apiKey}
              namespaces={namespaces}
              onModelUpdated={(model) => setMe((prev) => (prev ? { ...prev, chatModel: model } : prev))}
              onUnauthorized={handleLogout}
              toast={toast}
            />
          )}
        </div>
      </main>

      {/* 全局 Toast */}
      {toastState && (
        <div
          className={`fixed bottom-5 right-5 z-50 max-w-md rounded-xl border px-4 py-2.5 text-sm shadow-xl ${
            toastState.isError
              ? "border-[#f85149]/50 bg-[#1a1114] text-[#ff7b72]"
              : "border-[#3fd0c9]/40 bg-[#111821] text-[#e6edf3]"
          }`}
        >
          {toastState.msg}
        </div>
      )}
    </div>
  );
}

export default App;
