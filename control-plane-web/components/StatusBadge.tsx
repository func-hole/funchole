const STATUS_STYLES: Record<string, string> = {
  ACTIVE: "bg-emerald-100 text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-400",
  VERIFIED: "bg-emerald-100 text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-400",
  ADOPTED: "bg-emerald-100 text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-400",
  PENDING: "bg-amber-100 text-amber-700 dark:bg-amber-500/10 dark:text-amber-400",
  DRAFT: "bg-amber-100 text-amber-700 dark:bg-amber-500/10 dark:text-amber-400",
  FAILED: "bg-rose-100 text-rose-700 dark:bg-rose-500/10 dark:text-rose-400",
  REJECTED: "bg-rose-100 text-rose-700 dark:bg-rose-500/10 dark:text-rose-400",
  EXPIRED: "bg-rose-100 text-rose-700 dark:bg-rose-500/10 dark:text-rose-400",
  ARCHIVED: "bg-rose-100 text-rose-700 dark:bg-rose-500/10 dark:text-rose-400",
  INACTIVE: "bg-slate-200 text-slate-600 dark:bg-slate-800 dark:text-slate-400",
};

export function StatusBadge({ status }: { status: string }) {
  const style = STATUS_STYLES[status] ?? "bg-slate-200 text-slate-600 dark:bg-slate-800 dark:text-slate-400";
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${style}`}
    >
      <span className="h-1.5 w-1.5 rounded-full bg-current" />
      {status}
    </span>
  );
}
