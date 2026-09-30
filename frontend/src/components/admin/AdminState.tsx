import type { ReactNode } from "react";
import { Inbox } from "lucide-react";

import { cn } from "@/lib/utils";

interface AdminEmptyProps {
  icon?: ReactNode;
  title: string;
  description?: string;
  action?: ReactNode;
  className?: string;
}

/**
 * 管理后台统一的空状态占位：品牌描边圆底 + 提示文案 + 可选操作。
 * 替代各 CRUD 页里那行光秃秃的"暂无 XX"文字，视觉上与 trace-list 空态对齐。
 */
export function AdminEmpty({
  icon,
  title,
  description,
  action,
  className
}: AdminEmptyProps) {
  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center px-6 py-12 text-center",
        className
      )}
    >
      <span className="flex h-14 w-14 items-center justify-center rounded-full border border-[#E6E6EF] bg-gradient-to-br from-[var(--violet-soft)] to-[#EEF2FF] text-[var(--accent-violet)] shadow-sm">
        {icon ?? <Inbox className="h-6 w-6" />}
      </span>
      <p className="mt-4 text-sm font-medium text-[var(--text-secondary)]">{title}</p>
      {description ? (
        <p className="mt-1 max-w-sm text-xs leading-relaxed text-[var(--text-tertiary)]">{description}</p>
      ) : null}
      {action ? <div className="mt-4">{action}</div> : null}
    </div>
  );
}

interface AdminSkeletonProps {
  rows?: number;
  className?: string;
}

/**
 * 管理后台表格加载骨架屏，替代"加载中..."文字。
 */
export function AdminSkeleton({ rows = 5, className }: AdminSkeletonProps) {
  return (
    <div className={cn("space-y-3 px-4 py-4", className)} aria-label="加载中">
      {Array.from({ length: rows }).map((_, i) => (
        <div
          key={i}
          className="flex items-center gap-4"
          style={{ opacity: 1 - i * 0.12 }}
        >
          <div className="h-9 w-9 shrink-0 animate-pulse rounded-lg bg-[color-mix(in_srgb,var(--bg-hover)_70%,transparent)]" />
          <div className="flex-1 space-y-2">
            <div className="h-3 w-3/5 animate-pulse rounded bg-[color-mix(in_srgb,var(--bg-hover)_80%,transparent)]" />
            <div className="h-3 w-2/5 animate-pulse rounded bg-[var(--bg-tertiary)]" />
          </div>
          <div className="h-6 w-16 shrink-0 animate-pulse rounded-full bg-[var(--bg-tertiary)]" />
        </div>
      ))}
    </div>
  );
}

/**
 * 表格已全部写死行高的 LoadingCell，比撑整行的骨架更紧凑。
 */
export function AdminTableLoading({
  colSpan,
  rows = 4
}: {
  colSpan: number;
  rows?: number;
}) {
  return (
    <tr>
      <td colSpan={colSpan} className="px-4 py-2">
        <AdminSkeleton rows={rows} className="px-0 py-2" />
      </td>
    </tr>
  );
}
