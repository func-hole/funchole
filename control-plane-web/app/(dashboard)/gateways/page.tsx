"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Pagination } from "@/components/Pagination";
import { StatusBadge } from "@/components/StatusBadge";
import { api, ApiError } from "@/lib/api";
import type {
  DomainResponse,
  GatewayResponse,
  GatewayStatus,
  PaginationResponse,
} from "@/lib/types";

const PAGE_SIZE = 10;

interface GatewayFormState {
  name: string;
  description: string;
  appDomainId: string;
  status: GatewayStatus;
}

const EMPTY_FORM: GatewayFormState = {
  name: "",
  description: "",
  appDomainId: "",
  status: "ACTIVE",
};

export default function GatewaysPage() {
  const [gateways, setGateways] = useState<PaginationResponse<GatewayResponse> | null>(null);
  const [domains, setDomains] = useState<DomainResponse[]>([]);
  const [page, setPage] = useState(1);
  const [reloadKey, setReloadKey] = useState(0);
  const [form, setForm] = useState<GatewayFormState | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await api.listGateways(page, PAGE_SIZE);
        if (!cancelled) setGateways(data);
      } catch {
        if (!cancelled) setError("Failed to load gateways");
      }
      try {
        const domainData = await api.listDomains(1, 100);
        if (!cancelled) {
          setDomains(domainData.items.filter((d) => d.status === "VERIFIED"));
        }
      } catch {
        if (!cancelled) setDomains([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [page, reloadKey]);

  function refresh() {
    setReloadKey((key) => key + 1);
  }

  function openCreate() {
    setEditingId(null);
    setForm({ ...EMPTY_FORM, appDomainId: domains[0]?.id ?? "" });
  }

  function openEdit(gateway: GatewayResponse) {
    setEditingId(gateway.id);
    setForm({
      name: gateway.name,
      description: gateway.description ?? "",
      appDomainId: gateway.appDomainId,
      status: gateway.status,
    });
  }

  function closeForm() {
    setForm(null);
    setEditingId(null);
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!form) return;
    setError(null);
    setBusy(true);
    const payload = {
      name: form.name.trim(),
      description: form.description.trim() || null,
      appDomainId: form.appDomainId,
      status: form.status,
    };
    try {
      if (editingId) {
        await api.updateGateway(editingId, payload);
      } else {
        await api.createGateway(payload);
      }
      closeForm();
      refresh();
    } catch (err) {
      setError(
        err instanceof ApiError ? err.message : "Failed to save gateway"
      );
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete(gateway: GatewayResponse) {
    if (!window.confirm(`Delete gateway "${gateway.name}"?`)) {
      return;
    }
    setError(null);
    try {
      await api.deleteGateway(gateway.id);
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to delete gateway");
    }
  }

  const inputClass =
    "h-10 w-full rounded-lg border border-zinc-300 bg-white px-3 text-sm text-zinc-950 outline-none focus:border-zinc-950 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-50 dark:focus:border-zinc-100";

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-zinc-950 dark:text-zinc-50">
            Gateways
          </h1>
          <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
            Each gateway gets a unique key under a verified domain.
          </p>
        </div>
        <button
          type="button"
          onClick={openCreate}
          disabled={domains.length === 0}
          className="h-10 rounded-lg bg-zinc-950 px-4 text-sm font-medium text-white transition-colors hover:bg-zinc-800 disabled:opacity-50 dark:bg-white dark:text-zinc-950 dark:hover:bg-zinc-200"
        >
          New gateway
        </button>
      </div>

      {domains.length === 0 && (
        <p className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-700 dark:bg-amber-950/50 dark:text-amber-400">
          You need at least one verified domain before creating a gateway.
        </p>
      )}

      {form && (
        <form
          onSubmit={handleSubmit}
          className="grid gap-4 rounded-xl border border-zinc-200 bg-white p-4 sm:grid-cols-2 dark:border-zinc-800 dark:bg-zinc-950"
        >
          <label className="flex flex-col gap-1.5">
            <span className="text-sm font-medium text-zinc-700 dark:text-zinc-300">Name</span>
            <input
              type="text"
              required
              maxLength={100}
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              className={inputClass}
            />
          </label>
          <label className="flex flex-col gap-1.5">
            <span className="text-sm font-medium text-zinc-700 dark:text-zinc-300">
              Description
            </span>
            <input
              type="text"
              maxLength={1000}
              value={form.description}
              onChange={(e) => setForm({ ...form, description: e.target.value })}
              className={inputClass}
            />
          </label>
          <label className="flex flex-col gap-1.5">
            <span className="text-sm font-medium text-zinc-700 dark:text-zinc-300">
              Domain
            </span>
            <select
              required
              value={form.appDomainId}
              onChange={(e) => setForm({ ...form, appDomainId: e.target.value })}
              className={inputClass}
            >
              {domains.map((domain) => (
                <option key={domain.id} value={domain.id}>
                  {domain.domainName}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1.5">
            <span className="text-sm font-medium text-zinc-700 dark:text-zinc-300">
              Status
            </span>
            <select
              value={form.status}
              onChange={(e) => setForm({ ...form, status: e.target.value as GatewayStatus })}
              className={inputClass}
            >
              <option value="ACTIVE">ACTIVE</option>
              <option value="INACTIVE">INACTIVE</option>
            </select>
          </label>
          <div className="flex gap-2 sm:col-span-2">
            <button
              type="submit"
              disabled={busy}
              className="h-10 rounded-lg bg-zinc-950 px-4 text-sm font-medium text-white transition-colors hover:bg-zinc-800 disabled:opacity-50 dark:bg-white dark:text-zinc-950 dark:hover:bg-zinc-200"
            >
              {editingId ? "Save changes" : "Create gateway"}
            </button>
            <button
              type="button"
              onClick={closeForm}
              className="h-10 rounded-lg border border-zinc-200 px-4 text-sm transition-colors hover:bg-zinc-100 dark:border-zinc-700 dark:hover:bg-zinc-800"
            >
              Cancel
            </button>
          </div>
        </form>
      )}

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
              <th className="px-4 py-3 font-medium">Name</th>
              <th className="px-4 py-3 font-medium">Host</th>
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="px-4 py-3 font-medium">Certificate</th>
              <th className="px-4 py-3 text-right font-medium">Actions</th>
            </tr>
          </thead>
          <tbody>
            {!gateways && (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-zinc-500">
                  Loading…
                </td>
              </tr>
            )}
            {gateways?.items.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-zinc-500">
                  No gateways yet.
                </td>
              </tr>
            )}
            {gateways?.items.map((gateway) => (
              <tr
                key={gateway.id}
                className="border-b border-zinc-100 last:border-0 dark:border-zinc-900"
              >
                <td className="px-4 py-3">
                  <p className="font-medium text-zinc-950 dark:text-zinc-50">{gateway.name}</p>
                  {gateway.description && (
                    <p className="text-xs text-zinc-500 dark:text-zinc-400">
                      {gateway.description}
                    </p>
                  )}
                </td>
                <td className="px-4 py-3 font-mono text-xs text-zinc-500 dark:text-zinc-400">
                  {gateway.uniqueKey}.{gateway.domainName}
                </td>
                <td className="px-4 py-3">
                  <StatusBadge status={gateway.status} />
                </td>
                <td className="px-4 py-3">
                  {gateway.certificate ? (
                    <div className="flex items-center gap-2">
                      <StatusBadge status={gateway.certificate.status} />
                    </div>
                  ) : (
                    <span className="text-xs text-zinc-500 dark:text-zinc-400">—</span>
                  )}
                </td>
                <td className="px-4 py-3 text-right">
                  <div className="flex justify-end gap-2">
                    <button
                      type="button"
                      onClick={() => openEdit(gateway)}
                      className="rounded-lg border border-zinc-200 px-3 py-1.5 text-xs transition-colors hover:bg-zinc-100 dark:border-zinc-700 dark:hover:bg-zinc-800"
                    >
                      Edit
                    </button>
                    <button
                      type="button"
                      onClick={() => handleDelete(gateway)}
                      className="rounded-lg border border-red-200 px-3 py-1.5 text-xs text-red-600 transition-colors hover:bg-red-50 dark:border-red-900 dark:text-red-400 dark:hover:bg-red-950/50"
                    >
                      Delete
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {gateways && (
        <Pagination
          page={gateways.page}
          totalPages={gateways.totalPages}
          totalElements={gateways.totalElements}
          onChange={setPage}
        />
      )}
    </div>
  );
}
