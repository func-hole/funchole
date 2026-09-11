"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { ProfileResponse } from "@/lib/types";
import { Panel } from "@/components/Panel";
import { WorkflowIcon, ServerIcon, GlobeIcon } from "@/components/icons";

export default function OverviewPage() {
  const [profile, setProfile] = useState<ProfileResponse | null>(null);
  const [domainCount, setDomainCount] = useState<number | null>(null);
  const [gatewayCount, setGatewayCount] = useState<number | null>(null);
  const [flowCount, setFlowCount] = useState<number | null>(null);

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
    api
      .listFlows(1, 1)
      .then((r) => active && setFlowCount(r.totalElements))
      .catch(() => {});
    return () => {
      active = false;
    };
  }, []);

  const cards = [
    { href: "/flows", label: "Flows", value: flowCount, icon: WorkflowIcon, accent: "text-cyan-600 dark:text-cyan-400" },
    { href: "/gateways", label: "Gateways", value: gatewayCount, icon: ServerIcon, accent: "text-violet-600 dark:text-violet-400" },
    { href: "/domains", label: "Domains", value: domainCount, icon: GlobeIcon, accent: "text-emerald-600 dark:text-emerald-400" },
  ];

  return (
    <div className="flex flex-col gap-8">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-foreground">
          Welcome{profile ? `, ${profile.username}` : ""}
        </h1>
        <p className="mt-1 text-sm text-muted">Manage your flows, gateways, and domains from here.</p>
      </div>

      <div className="grid gap-4 sm:grid-cols-3">
        {cards.map((card) => {
          const Icon = card.icon;
          return (
            <Link key={card.href} href={card.href}>
              <Panel className="group p-6 transition-colors hover:border-border-strong">
                <div className="flex items-center justify-between">
                  <p className="text-sm text-muted">{card.label}</p>
                  <Icon className={`h-5 w-5 ${card.accent}`} />
                </div>
                <p className="mt-3 text-3xl font-semibold text-foreground">{card.value ?? "—"}</p>
              </Panel>
            </Link>
          );
        })}
      </div>
    </div>
  );
}
