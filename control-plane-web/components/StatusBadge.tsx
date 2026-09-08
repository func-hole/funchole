const STATUS_STYLES: Record<string, string> = {
  ACTIVE: "bg-green-100 text-green-700 dark:bg-green-950/60 dark:text-green-400",
  VERIFIED: "bg-green-100 text-green-700 dark:bg-green-950/60 dark:text-green-400",
  PENDING: "bg-amber-100 text-amber-700 dark:bg-amber-950/60 dark:text-amber-400",
  FAILED: "bg-red-100 text-red-700 dark:bg-red-950/60 dark:text-red-400",
  REJECTED: "bg-red-100 text-red-700 dark:bg-red-950/60 dark:text-red-400",
  EXPIRED: "bg-red-100 text-red-700 dark:bg-red-950/60 dark:text-red-400",
  INACTIVE: "bg-zinc-200 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400",
};

export function StatusBadge({ status }: { status: string }) {
  const style =
    STATUS_STYLES[status] ??
    "bg-zinc-200 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400";
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${style}`}
    >
      {status}
    </span>
  );
}
