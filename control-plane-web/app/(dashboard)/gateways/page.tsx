"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Pagination } from "@/components/Pagination";
import { StatusBadge } from "@/components/StatusBadge";
import { Panel, panelClass } from "@/components/Panel";
import { Button } from "@/components/Button";
import { inputClass, labelClass, fieldClass } from "@/components/Input";
import { PlusIcon, PencilIcon, TrashIcon } from "@/components/icons";
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
      setError(err instanceof ApiError ? err.message : "Failed to save gateway");
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

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-foreground">Gateways</h1>
          <p className="mt-1 text-sm text-muted">Each gateway gets a unique key under a verified domain.</p>
        </div>
        <Button variant="primary" onClick={openCreate} disabled={domains.length === 0}>
          <PlusIcon className="h-4 w-4" />
          New gateway
        </Button>
      </div>

      {domains.length === 0 && (
        <p className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-700 dark:bg-amber-500/10 dark:text-amber-400">
          You need at least one verified domain before creating a gateway.
        </p>
      )}

      {form && (
        <form onSubmit={handleSubmit} className={`${panelClass} grid gap-4 p-4 sm:grid-cols-2`}>
          <label className={fieldClass}>
            <span className={labelClass}>Name</span>
            <input
              type="text"
              required
              maxLength={100}
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              className={inputClass}
            />
          </label>
          <label className={fieldClass}>
            <span className={labelClass}>Description</span>
            <input
              type="text"
              maxLength={1000}
              value={form.description}
              onChange={(e) => setForm({ ...form, description: e.target.value })}
              className={inputClass}
            />
          </label>
          <label className={fieldClass}>
            <span className={labelClass}>Domain</span>
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
          <label className={fieldClass}>
            <span className={labelClass}>Status</span>
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
            <Button type="submit" variant="primary" disabled={busy}>
              {editingId ? "Save changes" : "Create gateway"}
            </Button>
            <Button type="button" variant="secondary" onClick={closeForm}>
              Cancel
            </Button>
          </div>
        </form>
      )}

      {error && (
        <p role="alert" className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-600 dark:bg-rose-500/10 dark:text-rose-400">
          {error}
        </p>
      )}

      <Panel className="overflow-hidden">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-border text-left text-muted">
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
                <td colSpan={5} className="px-4 py-8 text-center text-muted">
                  Loading…
                </td>
              </tr>
            )}
            {gateways?.items.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-muted">
                  No gateways yet.
                </td>
              </tr>
            )}
            {gateways?.items.map((gateway) => (
              <tr key={gateway.id} className="border-b border-border last:border-0 hover:bg-surface-hover">
                <td className="px-4 py-3">
                  <p className="font-medium text-foreground">{gateway.name}</p>
                  {gateway.description && <p className="text-xs text-muted">{gateway.description}</p>}
                </td>
                <td className="px-4 py-3 font-mono text-xs text-muted">
                  {gateway.uniqueKey}.{gateway.domainName}
                </td>
                <td className="px-4 py-3">
                  <StatusBadge status={gateway.status} />
                </td>
                <td className="px-4 py-3">
                  {gateway.certificate ? (
                    <StatusBadge status={gateway.certificate.status} />
                  ) : (
                    <span className="text-xs text-muted">—</span>
                  )}
                </td>
                <td className="px-4 py-3 text-right">
                  <div className="flex justify-end gap-2">
                    <Button variant="ghost" size="icon" title="Edit" onClick={() => openEdit(gateway)}>
                      <PencilIcon className="h-4 w-4" />
                    </Button>
                    <Button variant="danger" size="icon" title="Delete" onClick={() => handleDelete(gateway)}>
                      <TrashIcon className="h-4 w-4" />
                    </Button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Panel>

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
