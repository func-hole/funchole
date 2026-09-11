"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { api } from "@/lib/api";
import { clearToken } from "@/lib/auth";
import type { ProfileResponse } from "@/lib/types";
import { GridIcon, WorkflowIcon, ServerIcon, GlobeIcon, LogOutIcon } from "@/components/icons";

const NAV_ITEMS = [
  { href: "/", label: "Overview", icon: GridIcon },
  { href: "/flows", label: "Flows", icon: WorkflowIcon },
  { href: "/gateways", label: "Gateways", icon: ServerIcon },
  { href: "/domains", label: "Domains", icon: GlobeIcon },
];

export default function DashboardLayout({ children }: { children: ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const [profile, setProfile] = useState<ProfileResponse | null>(null);

  useEffect(() => {
    let active = true;
    api
      .getProfile()
      .then((p) => {
        if (active) setProfile(p);
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, []);

  function handleLogout() {
    clearToken();
    router.replace("/login");
  }

  function isActive(href: string) {
    return href === "/" ? pathname === "/" : pathname.startsWith(href);
  }

  const currentSection = NAV_ITEMS.find((item) => isActive(item.href))?.label ?? "FuncHole";

  return (
    <div className="flex min-h-screen bg-background">
      <aside className="fixed inset-y-0 left-0 z-20 flex w-16 flex-col items-center gap-1 border-r border-border bg-surface py-4">
        <Link
          href="/"
          className="mb-3 flex h-9 w-9 items-center justify-center rounded-lg bg-cyan-600 text-sm font-bold text-white dark:bg-cyan-500 dark:text-slate-950"
        >
          F
        </Link>
        <nav className="flex flex-col items-center gap-1">
          {NAV_ITEMS.map((item) => {
            const active = isActive(item.href);
            const Icon = item.icon;
            return (
              <Link
                key={item.href}
                href={item.href}
                title={item.label}
                aria-label={item.label}
                aria-current={active ? "page" : undefined}
                className={`group relative flex h-10 w-10 items-center justify-center rounded-lg transition-colors ${
                  active
                    ? "bg-cyan-500/10 text-cyan-600 dark:text-cyan-400"
                    : "text-muted hover:bg-surface-hover hover:text-foreground"
                }`}
              >
                <Icon className="h-5 w-5" />
                {active && (
                  <span className="absolute -left-1 h-5 w-0.5 rounded-full bg-cyan-500 dark:bg-cyan-400" />
                )}
              </Link>
            );
          })}
        </nav>
      </aside>

      <div className="flex min-h-screen flex-1 flex-col pl-16">
        <header className="sticky top-0 z-10 flex h-14 items-center justify-between border-b border-border bg-surface/90 px-6 backdrop-blur">
          <div className="flex items-center gap-2 text-sm">
            <span className="font-semibold tracking-tight text-foreground">FuncHole</span>
            <ChevronDivider />
            <span className="text-muted">{currentSection}</span>
          </div>
          <div className="flex items-center gap-3">
            <span className="text-sm text-muted">{profile?.username ?? "…"}</span>
            <button
              type="button"
              onClick={handleLogout}
              aria-label="Sign out"
              title="Sign out"
              className="flex h-8 w-8 cursor-pointer items-center justify-center rounded-lg border border-border text-muted transition-colors hover:bg-surface-hover hover:text-foreground"
            >
              <LogOutIcon className="h-4 w-4" />
            </button>
          </div>
        </header>
        <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-8">{children}</main>
      </div>
    </div>
  );
}

function ChevronDivider() {
  return <span className="text-border-strong">/</span>;
}
