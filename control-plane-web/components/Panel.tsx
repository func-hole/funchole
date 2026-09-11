import type { HTMLAttributes } from "react";

export const panelClass = "rounded-xl border border-border bg-surface";

export function Panel({ className = "", ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={`${panelClass} ${className}`} {...props} />;
}
