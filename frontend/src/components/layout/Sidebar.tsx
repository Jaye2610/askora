import * as React from "react";
import { differenceInCalendarDays, isValid } from "date-fns";
import {
  KeyRound,
  LogOut,
  MessageSquare,
  MoreHorizontal,
  Pencil,
  Search,
  Trash2
} from "lucide-react";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";

import { AskoraLogo } from "@/components/brand/AskoraLogo";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle
} from "@/components/ui/alert-dialog";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger
} from "@/components/ui/dropdown-menu";
import { Loading } from "@/components/common/Loading";
import { cn } from "@/lib/utils";
import { changePassword } from "@/services/userService";
import { useAuthStore } from "@/stores/authStore";
import { useChatStore } from "@/stores/chatStore";

// 侧边栏快捷提问预设

interface SidebarProps {
  isOpen: boolean;
  onClose: () => void;
}

export function Sidebar({ isOpen, onClose }: SidebarProps) {
  const {
    sessions,
    currentSessionId,
    isLoading,
    sessionsLoaded,
    deleteSession,
    renameSession,
    selectSession,
    fetchSessions,
  } = useChatStore();
  const navigate = useNavigate();
  const { user, logout } = useAuthStore();
  const [query, setQuery] = React.useState("");
  const [renamingId, setRenamingId] = React.useState<string | null>(null);
  const [renameValue, setRenameValue] = React.useState("");
  const [deleteTarget, setDeleteTarget] = React.useState<{
    id: string;
    title: string;
  } | null>(null);
  const renameInputRef = React.useRef<HTMLInputElement | null>(null);
  const [pwdOpen, setPwdOpen] = React.useState(false);
  const [pwdSaving, setPwdSaving] = React.useState(false);
  const [pwdForm, setPwdForm] = React.useState({ currentPassword: "", newPassword: "" });

  const openPwdDialog = () => {
    setPwdForm({ currentPassword: "", newPassword: "" });
    setPwdOpen(true);
  };

  const handleChangePassword = async () => {
    if (!pwdForm.currentPassword || !pwdForm.newPassword) {
      toast.error("请填写当前密码与新密码");
      return;
    }
    if (pwdForm.newPassword.length < 6) {
      toast.error("新密码至少 6 位");
      return;
    }
    try {
      setPwdSaving(true);
      await changePassword({
        currentPassword: pwdForm.currentPassword,
        newPassword: pwdForm.newPassword
      });
      toast.success("密码修改成功");
      setPwdOpen(false);
    } catch (error) {
      toast.error((error as Error).message || "密码修改失败");
    } finally {
      setPwdSaving(false);
    }
  };


  React.useEffect(() => {
    if (sessions.length === 0) {
      fetchSessions().catch(() => null);
    }
  }, [fetchSessions, sessions.length]);

  const filteredSessions = React.useMemo(() => {
    const keyword = query.trim().toLowerCase();
    if (!keyword) return sessions;
    return sessions.filter((session) => {
      const title = (session.title || "新对话").toLowerCase();
      return title.includes(keyword) || session.id.toLowerCase().includes(keyword);
    });
  }, [query, sessions]);

  const groupedSessions = React.useMemo(() => {
    const now = new Date();
    const groups = new Map<string, typeof filteredSessions>();
    const order: string[] = [];

    const resolveLabel = (value?: string) => {
      const parsed = value ? new Date(value) : now;
      const date = isValid(parsed) ? parsed : now;
      const diff = Math.max(0, differenceInCalendarDays(now, date));
      if (diff === 0) return "今天";
      if (diff <= 7) return "7天内";
      if (diff <= 30) return "30天内";
      return "更早";
    };

    filteredSessions.forEach((session) => {
      const label = resolveLabel(session.lastTime);
      if (!groups.has(label)) {
        groups.set(label, []);
        order.push(label);
      }
      groups.get(label)?.push(session);
    });

    return order.map((label) => ({
      label,
      items: groups.get(label) || []
    }));
  }, [filteredSessions]);

  React.useEffect(() => {
    if (renamingId) {
      renameInputRef.current?.focus();
      renameInputRef.current?.select();
    }
  }, [renamingId]);

  const startRename = (id: string, title: string) => {
    setRenamingId(id);
    setRenameValue(title || "新对话");
  };

  const cancelRename = () => {
    setRenamingId(null);
    setRenameValue("");
  };

  const commitRename = async () => {
    if (!renamingId) return;
    const nextTitle = renameValue.trim();
    if (!nextTitle) {
      cancelRename();
      return;
    }
    const currentTitle = sessions.find((session) => session.id === renamingId)?.title || "新对话";
    if (nextTitle === currentTitle) {
      cancelRename();
      return;
    }
    await renameSession(renamingId, nextTitle);
    cancelRename();
  };

  return (
    <>
      <div
        className={cn(
          "fixed inset-0 z-30 bg-slate-900/30 backdrop-blur-sm transition-opacity lg:hidden",
          isOpen ? "opacity-100" : "pointer-events-none opacity-0"
        )}
        onClick={onClose}
      />
      <aside
        className={cn(
          "fixed left-0 top-0 z-40 flex h-screen w-[280px] flex-shrink-0 flex-col bg-[var(--bg-secondary)] p-3 transition-transform lg:static lg:h-screen lg:translate-x-0",
          isOpen ? "translate-x-0" : "-translate-x-full"
        )}
      >
        <div className="border-b border-[var(--border-light)] pb-3">
          <div className="flex items-center gap-3">
            <AskoraLogo size={40} title="Askora AI" className="shadow-[0_2px_8px_rgba(124,58,237,0.35)]" />
            <div className="min-w-0">
              <p className="truncate text-base font-semibold text-[var(--text-primary)]">Askora AI 智能体</p>
            </div>
          </div>
        </div>
        <div className="py-3 space-y-4">
          <div className="rounded-2xl border border-[var(--border-light)] bg-[var(--bg-primary)] p-3 shadow-[0_12px_26px_rgba(15,23,42,0.06)]">
            <div className="flex items-center justify-between px-1">
              <span className="text-[var(--text-2xs)] font-semibold text-[var(--text-tertiary)]">搜索对话</span>
              <span className="text-[var(--text-2xs)] text-[var(--violet-line)]">Ctrl / Cmd + K</span>
            </div>
            <div className="mt-2">
              <div className="relative">
                <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[var(--text-tertiary)]" />
                <input
                  value={query}
                  onChange={(event) => setQuery(event.target.value)}
                  placeholder="搜索对话..."
                  className="h-10 w-full rounded-xl border border-[var(--border-default)] bg-[var(--bg-secondary)] pl-9 pr-3 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-tertiary)] focus:border-[var(--violet-line)] focus:outline-none transition-colors"
                />
              </div>
            </div>
          </div>
        </div>
        <div className="relative flex-1 min-h-0">
          <div className="h-full overflow-y-auto sidebar-scroll">
            {sessions.length === 0 && (!sessionsLoaded || isLoading) ? (
              <div
                className="flex h-full items-center justify-center text-[var(--text-tertiary)]"
              >
                <Loading label="加载会话中" />
              </div>
            ) : filteredSessions.length === 0 ? (
              <div
                className="flex h-full flex-col items-center justify-center text-[var(--text-tertiary)]"
              >
                <MessageSquare className="h-16 w-16" />
                <p className="mt-2 text-[var(--text-base)]">暂无对话记录</p>
              </div>
            ) : (
              <div>
                {groupedSessions.map((group, index) => (
                  <div key={group.label} className={cn("flex flex-col", index === 0 ? "mt-0" : "mt-4")}>
                    <p className="mb-1.5 pl-3 text-[var(--text-xs)] font-normal leading-[18px] text-[var(--text-tertiary)]">
                      {group.label}
                    </p>
                    {group.items.map((session) => (
                      <div
                        key={session.id}
                        className={cn(
                          "group my-[1px] flex min-h-[40px] cursor-pointer items-center justify-between gap-2 rounded-lg px-3 py-2 text-[var(--text-base)] leading-[22px] transition-colors duration-200",
                          currentSessionId === session.id
                            ? "bg-[var(--violet-surface)] text-[var(--violet-text)]"
                            : "text-[#333333] hover:bg-[var(--bg-tertiary)]"
                        )}
                        role="button"
                        tabIndex={0}
                        onClick={() => {
                          if (renamingId === session.id) return;
                          if (renamingId) {
                            cancelRename();
                          }
                          selectSession(session.id).catch(() => null);
                          navigate(`/chat/${session.id}`);
                          onClose();
                        }}
                        onKeyDown={(event) => {
                          if (event.key === "Enter") {
                            selectSession(session.id).catch(() => null);
                            navigate(`/chat/${session.id}`);
                            onClose();
                          }
                        }}
                      >
                        {renamingId === session.id ? (
                          <input
                            ref={renameInputRef}
                            value={renameValue}
                            onChange={(event) => setRenameValue(event.target.value)}
                            onClick={(event) => event.stopPropagation()}
                            onKeyDown={(event) => {
                              if (event.key === "Enter") {
                                event.preventDefault();
                                commitRename().catch(() => null);
                              }
                              if (event.key === "Escape") {
                                event.preventDefault();
                                cancelRename();
                              }
                            }}
                            onBlur={() => {
                              commitRename().catch(() => null);
                            }}
                            className="h-6 flex-1 rounded-md border border-[var(--border-default)] bg-[var(--bg-primary)] px-2 text-[var(--text-base)] leading-[22px] text-[#333333] focus:border-[var(--accent-violet)] focus:outline-none"
                          />
                        ) : (
                          <span className="min-w-0 flex-1 truncate font-normal">
                            {session.title || "新对话"}
                          </span>
                        )}
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild>
                            <button
                              type="button"
                              className={cn(
                                "flex h-6 w-6 items-center justify-center rounded text-[#666666] transition-opacity duration-150 hover:bg-[rgba(0,0,0,0.06)]",
                                currentSessionId === session.id
                                  ? "pointer-events-auto opacity-100 text-[var(--accent-violet)]"
                                  : "pointer-events-none opacity-0 group-hover:pointer-events-auto group-hover:opacity-100"
                              )}
                              onClick={(event) => event.stopPropagation()}
                              aria-label="会话操作"
                            >
                              <MoreHorizontal className="h-4 w-4" />
                            </button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent
                            align="start"
                            className="min-w-[120px] rounded-lg border-0 bg-[var(--bg-primary)] p-0 py-1 shadow-[0_4px_16px_rgba(0,0,0,0.12)]"
                          >
                            <DropdownMenuItem
                              onClick={(event) => {
                                event.stopPropagation();
                                startRename(session.id, session.title || "新对话");
                              }}
                              className="px-4 py-2 text-[var(--text-base)] text-[#333333] focus:bg-[var(--bg-tertiary)] focus:text-[#333333] data-[highlighted]:bg-[var(--bg-tertiary)] data-[highlighted]:text-[#333333]"
                            >
                              <Pencil className="mr-2 h-4 w-4" />
                              重命名
                            </DropdownMenuItem>
                            <DropdownMenuItem
                              onClick={(event) => {
                                event.stopPropagation();
                                setDeleteTarget({
                                  id: session.id,
                                  title: session.title || "新对话"
                                });
                              }}
                              className="px-4 py-2 text-[var(--text-base)] text-[#FF4D4F] focus:bg-[var(--bg-tertiary)] focus:text-[#FF4D4F] data-[highlighted]:bg-[var(--bg-tertiary)] data-[highlighted]:text-[#FF4D4F]"
                            >
                              <Trash2 className="mr-2 h-4 w-4" />
                              删除
                            </DropdownMenuItem>
                          </DropdownMenuContent>
                        </DropdownMenu>
                      </div>
                    ))}
                  </div>
                ))}
              </div>
            )}
          </div>
          <div
            aria-hidden="true"
            className="pointer-events-none absolute inset-x-0 bottom-0 z-10 h-5 bg-gradient-to-b from-transparent to-[var(--bg-secondary)]"
          />
        </div>
        <div className="mt-auto pt-3">
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button
                type="button"
                className="flex w-full items-center gap-2 rounded-lg p-2 text-left transition-colors hover:bg-[var(--bg-tertiary)] data-[state=open]:bg-[var(--bg-hover)]"
                aria-label="用户菜单"
              >
                <AskoraLogo size={32} className="!rounded-full" />
                <span className="flex-1 truncate text-sm font-medium text-[var(--text-primary)]">
                  {(() => {
                    const fallback = user?.username || user?.userId || "用户";
                    return /^\d+$/.test(fallback) ? "用户" : fallback;
                  })()}
                </span>
                <MoreHorizontal className="h-4 w-4 text-[var(--text-tertiary)]" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" side="top" sideOffset={8} className="w-48">
              <DropdownMenuItem onClick={openPwdDialog}>
                <KeyRound className="mr-2 h-4 w-4" />
                修改密码
              </DropdownMenuItem>
              <DropdownMenuItem onClick={() => logout()} className="text-[var(--danger-text)] focus:text-[var(--danger-text)]">
                <LogOut className="mr-2 h-4 w-4" />
                退出登录
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </aside>
      <Dialog open={pwdOpen} onOpenChange={setPwdOpen}>
        <DialogContent className="sm:max-w-[400px]">
          <DialogHeader>
            <DialogTitle>修改密码</DialogTitle>
            <DialogDescription>请先验证当前密码，再设置新密码。</DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-2">
              <label className="text-sm font-medium">当前密码</label>
              <Input
                type="password"
                value={pwdForm.currentPassword}
                onChange={(e) => setPwdForm((prev) => ({ ...prev, currentPassword: e.target.value }))}
                placeholder="请输入当前密码"
                autoComplete="current-password"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">新密码</label>
              <Input
                type="password"
                value={pwdForm.newPassword}
                onChange={(e) => setPwdForm((prev) => ({ ...prev, newPassword: e.target.value }))}
                placeholder="至少 6 位"
                autoComplete="new-password"
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setPwdOpen(false)} disabled={pwdSaving}>
              取消
            </Button>
            <Button onClick={handleChangePassword} disabled={pwdSaving}>
              {pwdSaving ? "保存中..." : "确认修改"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <AlertDialog open={Boolean(deleteTarget)} onOpenChange={(open) => {
        if (!open) {
          setDeleteTarget(null);
        }
      }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除该会话？</AlertDialogTitle>
            <AlertDialogDescription>
              [{deleteTarget?.title || "该会话"}] 将被永久删除，无法恢复。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (!deleteTarget) return;
                const target = deleteTarget;
                const isCurrent = currentSessionId === target.id;
                setDeleteTarget(null);
                deleteSession(target.id)
                  .then(() => {
                    if (isCurrent) {
                      navigate("/chat");
                    }
                  })
                  .catch(() => null);
              }}
            >
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
