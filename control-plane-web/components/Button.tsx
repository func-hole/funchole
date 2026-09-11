import type { ButtonHTMLAttributes } from "react";

export type ButtonVariant = "primary" | "secondary" | "danger" | "ghost";
export type ButtonSize = "md" | "sm" | "icon";

const VARIANT_CLASSES: Record<ButtonVariant, string> = {
  primary:
    "bg-cyan-600 text-white hover:bg-cyan-500 dark:bg-cyan-500 dark:text-slate-950 dark:hover:bg-cyan-400",
  secondary:
    "border border-border bg-surface text-foreground hover:bg-surface-hover",
  danger:
    "border border-rose-200 text-rose-600 hover:bg-rose-50 dark:border-rose-900/60 dark:text-rose-400 dark:hover:bg-rose-950/40",
  ghost: "text-muted hover:bg-surface-hover hover:text-foreground",
};

const SIZE_CLASSES: Record<ButtonSize, string> = {
  md: "h-9 px-4 text-sm",
  sm: "h-8 px-3 text-xs",
  icon: "h-8 w-8 p-0",
};

export function buttonClasses(variant: ButtonVariant = "secondary", size: ButtonSize = "md") {
  return `inline-flex cursor-pointer items-center justify-center gap-1.5 rounded-lg font-medium transition-colors disabled:pointer-events-none disabled:opacity-50 ${VARIANT_CLASSES[variant]} ${SIZE_CLASSES[size]}`;
}

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
}

export function Button({ variant = "secondary", size = "md", className = "", type = "button", ...props }: ButtonProps) {
  return (
    <button
      type={type}
      className={`${buttonClasses(variant, size)} ${className}`}
      {...props}
    />
  );
}
