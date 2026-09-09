"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { ProfileResponse } from "@/lib/types";

export default function OverviewPage() {
  const [profile, setProfile] = useState<ProfileResponse | null>(null);
  const [domainCount, setDomainCount] = useState<number | null>(null);
  const [gatewayCount, setGatewayCount] = useState<number | null>(null);

  useEffect(() => {
    let active = true;
    api.getProfile().then((p) => active && setProfile(p)).catch(() => {});
    api
      .listDomains(1, 1)
      .then((r) => active && setDomainCount(r.totalElements))
      .catch(() => {});
    api
      .listGateways(1, 1)
      .then((r) => active && setGatewayCount(r.totalElements))
      .catch(() => {});
    return () => {
      active = false;
    };
  }, []);

  return (
    <div className="flex flex-col gap-8">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-zinc-950 dark:text-zinc-50">
          Welcome{profile ? `, ${profile.username}` : ""}
        </h1>
        <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
          Manage your domains and gateways from here.
        </p>
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <Link
          href="/domains"
          className="rounded-xl border border-zinc-200 bg-white p-6 transition-colors hover:border-zinc-400 dark:border-zinc-800 dark:bg-zinc-950 dark:hover:border-zinc-600"
        >
          <p className="text-sm text-zinc-500 dark:text-zinc-400">Domains</p>
          <p className="mt-2 text-3xl font-semibold text-zinc-950 dark:text-zinc-50">
            {domainCount ?? "—"}
          </p>
        </Link>
        <Link
          href="/gateways"
          className="rounded-xl border border-zinc-200 bg-white p-6 transition-colors hover:border-zinc-400 dark:border-zinc-800 dark:bg-zinc-950 dark:hover:border-zinc-600"
        >
          <p className="text-sm text-zinc-500 dark:text-zinc-400">Gateways</p>
          <p className="mt-2 text-3xl font-semibold text-zinc-950 dark:text-zinc-50">
            {gatewayCount ?? "—"}
          </p>
        </Link>
      </div>
    </div>
  );
}
