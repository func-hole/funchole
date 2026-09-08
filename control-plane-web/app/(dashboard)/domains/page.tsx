"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Pagination } from "@/components/Pagination";
import { StatusBadge } from "@/components/StatusBadge";
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
        <h1 className="text-2xl font-semibold tracking-tight text-zinc-950 dark:text-zinc-50">
          Domains
        </h1>
        <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
          Register a domain, then start verification to prove ownership.
        </p>
      </div>

      <form
        onSubmit={handleCreate}
        className="flex flex-wrap items-start gap-3 rounded-xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-zinc-950"
      >
        <input
          type="text"
          required
          placeholder="example.com"
          value={domainName}
          onChange={(e) => setDomainName(e.target.value)}
          className="h-10 flex-1 min-w-56 rounded-lg border border-zinc-300 bg-white px-3 text-sm text-zinc-950 outline-none focus:border-zinc-950 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-50 dark:focus:border-zinc-100"
        />
        <button
          type="submit"
          disabled={busy}
          className="h-10 rounded-lg bg-zinc-950 px-4 text-sm font-medium text-white transition-colors hover:bg-zinc-800 disabled:opacity-50 dark:bg-white dark:text-zinc-950 dark:hover:bg-zinc-200"
        >
          Add domain
        </button>
      </form>

      {error && (
        <p
          role="alert"
          className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-600 dark:bg-red-950/50 dark:text-red-400"
        >
          {error}
        </p>
      )}

      <div className="overflow-hidden rounded-xl border border-zinc-200 bg-white dark:border-zinc-800 dark:bg-zinc-950">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-zinc-200 text-left text-zinc-500 dark:border-zinc-800 dark:text-zinc-400">
              <th className="px-4 py-3 font-medium">Domain</th>
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="px-4 py-3 font-medium">Verification code</th>
              <th className="px-4 py-3 text-right font-medium">Actions</th>
            </tr>
          </thead>
          <tbody>
            {!domains && (
              <tr>
                <td colSpan={4} className="px-4 py-8 text-center text-zinc-500">
                  Loading…
                </td>
              </tr>
            )}
            {domains?.items.length === 0 && (
              <tr>
                <td colSpan={4} className="px-4 py-8 text-center text-zinc-500">
                  No domains yet. Add one above.
                </td>
              </tr>
            )}
            {domains?.items.map((domain) => (
              <tr
                key={domain.id}
                className="border-b border-zinc-100 last:border-0 dark:border-zinc-900"
              >
                <td className="px-4 py-3 font-medium text-zinc-950 dark:text-zinc-50">
                  {domain.domainName}
                </td>
                <td className="px-4 py-3">
                  <StatusBadge status={domain.status} />
                </td>
                <td className="px-4 py-3 font-mono text-xs text-zinc-500 dark:text-zinc-400">
                  {domain.verificationCode ?? "—"}
                </td>
                <td className="px-4 py-3 text-right">
                  {domain.status === "PENDING" && (
                    <button
                      type="button"
                      onClick={() => handleVerify(domain)}
                      className="rounded-lg border border-zinc-200 px-3 py-1.5 text-xs transition-colors hover:bg-zinc-100 dark:border-zinc-700 dark:hover:bg-zinc-800"
                    >
                      Verify
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

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
