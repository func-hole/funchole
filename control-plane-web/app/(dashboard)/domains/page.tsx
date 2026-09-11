"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Pagination } from "@/components/Pagination";
import { StatusBadge } from "@/components/StatusBadge";
import { panelClass, Panel } from "@/components/Panel";
import { Button } from "@/components/Button";
import { inputClass } from "@/components/Input";
import { PlusIcon } from "@/components/icons";
import { api, ApiError } from "@/lib/api";
import type { DomainResponse, PaginationResponse } from "@/lib/types";

const PAGE_SIZE = 10;

export default function DomainsPage() {
  const [domains, setDomains] = useState<PaginationResponse<DomainResponse> | null>(null);
  const [page, setPage] = useState(1);
  const [reloadKey, setReloadKey] = useState(0);
  const [domainName, setDomainName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await api.listDomains(page, PAGE_SIZE);
        if (!cancelled) setDomains(data);
      } catch {
        if (!cancelled) setError("Failed to load domains");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [page, reloadKey]);

  function refresh() {
    setReloadKey((key) => key + 1);
  }

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await api.createDomain({ domainName: domainName.trim() });
      setDomainName("");
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to create domain");
    } finally {
      setBusy(false);
    }
  }

  async function handleVerify(domain: DomainResponse) {
    setError(null);
    try {
      await api.initiateDomainVerification(domain.id);
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to start verification");
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-foreground">Domains</h1>
        <p className="mt-1 text-sm text-muted">Register a domain, then start verification to prove ownership.</p>
      </div>

      <form onSubmit={handleCreate} className={`${panelClass} flex flex-wrap items-start gap-3 p-4`}>
        <input
          type="text"
          required
          placeholder="example.com"
          value={domainName}
          onChange={(e) => setDomainName(e.target.value)}
          className={`${inputClass} min-w-56 flex-1`}
        />
        <Button type="submit" variant="primary" disabled={busy}>
          <PlusIcon className="h-4 w-4" />
          Add domain
        </Button>
      </form>

      {error && (
        <p role="alert" className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-600 dark:bg-rose-500/10 dark:text-rose-400">
          {error}
        </p>
      )}

      <Panel className="overflow-hidden">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-border text-left text-muted">
              <th className="px-4 py-3 font-medium">Domain</th>
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="px-4 py-3 font-medium">Verification code</th>
              <th className="px-4 py-3 text-right font-medium">Actions</th>
            </tr>
          </thead>
          <tbody>
            {!domains && (
              <tr>
                <td colSpan={4} className="px-4 py-8 text-center text-muted">
                  Loading…
                </td>
              </tr>
            )}
            {domains?.items.length === 0 && (
              <tr>
                <td colSpan={4} className="px-4 py-8 text-center text-muted">
                  No domains yet. Add one above.
                </td>
              </tr>
            )}
            {domains?.items.map((domain) => (
              <tr key={domain.id} className="border-b border-border last:border-0 hover:bg-surface-hover">
                <td className="px-4 py-3 font-medium text-foreground">{domain.domainName}</td>
                <td className="px-4 py-3">
                  <StatusBadge status={domain.status} />
                </td>
                <td className="px-4 py-3 font-mono text-xs text-muted">
                  {domain.verificationCode ?? "—"}
                </td>
                <td className="px-4 py-3 text-right">
                  {domain.status === "PENDING" && (
                    <Button variant="secondary" size="sm" onClick={() => handleVerify(domain)}>
                      Verify
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Panel>

      {domains && (
        <Pagination
          page={domains.page}
          totalPages={domains.totalPages}
          totalElements={domains.totalElements}
          onChange={setPage}
        />
      )}
    </div>
  );
}
