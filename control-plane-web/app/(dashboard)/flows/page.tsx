"use client";

import Link from "next/link";
import { useEffect, useState, type FormEvent } from "react";
import { Pagination } from "@/components/Pagination";
import { StatusBadge } from "@/components/StatusBadge";
import { panelClass, Panel } from "@/components/Panel";
import { Button, buttonClasses } from "@/components/Button";
import { inputClass, labelClass, fieldClass } from "@/components/Input";
import { PlusIcon, TrashIcon } from "@/components/icons";
import { api, ApiError } from "@/lib/api";
import type { FlowResponse, GatewayResponse, PaginationResponse } from "@/lib/types";

const PAGE_SIZE = 10;
const HTTP_METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE"];

interface FlowFormState {
  flowKey: string;
  name: string;
  description: string;
  gatewayId: string;
  httpMethod: string;
  path: string;
  priority: string;
}

const EMPTY_FORM: FlowFormState = {
  flowKey: "",
  name: "",
  description: "",
  gatewayId: "",
  httpMethod: "GET",
  path: "/",
  priority: "100",
};

export default function FlowsPage() {
  const [flows, setFlows] = useState<PaginationResponse<FlowResponse> | null>(null);
  const [gateways, setGateways] = useState<GatewayResponse[]>([]);
  const [page, setPage] = useState(1);
  const [reloadKey, setReloadKey] = useState(0);
  const [form, setForm] = useState<FlowFormState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await api.listFlows(page, PAGE_SIZE);
        if (!cancelled) setFlows(data);
      } catch {
        if (!cancelled) setError("Failed to load flows");
      }
      try {
        const gatewayData = await api.listGateways(1, 100);
        if (!cancelled) setGateways(gatewayData.items);
      } catch {
        if (!cancelled) setGateways([]);
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
    setForm({ ...EMPTY_FORM, gatewayId: gateways[0]?.id ?? "" });
  }

  function closeForm() {
    setForm(null);
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!form) return;
    setError(null);
    setBusy(true);
    try {
      await api.createFlow({
        flowKey: form.flowKey.trim(),
        name: form.name.trim(),
        description: form.description.trim() || null,
        gatewayId: form.gatewayId,
        httpMethod: form.httpMethod,
        path: form.path.trim(),
        priority: form.priority.trim() ? Number(form.priority) : null,
      });
      closeForm();
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to create flow");
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete(flow: FlowResponse) {
    if (!window.confirm(`Delete flow "${flow.name}"?`)) {
      return;
    }
    setError(null);
    try {
      await api.deleteFlow(flow.id);
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to delete flow");
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-foreground">Flows</h1>
          <p className="mt-1 text-sm text-muted">
            A flow maps a route on a gateway to an ordered chain of steps.
          </p>
        </div>
        <Button variant="primary" onClick={openCreate} disabled={gateways.length === 0}>
          <PlusIcon className="h-4 w-4" />
          New flow
        </Button>
      </div>

      {gateways.length === 0 && (
        <p className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-700 dark:bg-amber-500/10 dark:text-amber-400">
          You need at least one gateway before creating a flow.
        </p>
      )}

      {form && (
        <form onSubmit={handleSubmit} className={`${panelClass} grid gap-4 p-4 sm:grid-cols-2`}>
          <label className={fieldClass}>
            <span className={labelClass}>Flow key</span>
            <input
              type="text"
              required
              maxLength={150}
              placeholder="flw_orders_list"
              pattern="[a-zA-Z0-9_.\-]+"
              value={form.flowKey}
              onChange={(e) => setForm({ ...form, flowKey: e.target.value })}
              className={`${inputClass} font-mono`}
            />
          </label>
          <label className={fieldClass}>
            <span className={labelClass}>Name</span>
            <input
              type="text"
              required
              maxLength={255}
              placeholder="Orders List"
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              className={inputClass}
            />
          </label>
          <label className={`${fieldClass} sm:col-span-2`}>
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
            <span className={labelClass}>Gateway</span>
            <select
              required
              value={form.gatewayId}
              onChange={(e) => setForm({ ...form, gatewayId: e.target.value })}
              className={inputClass}
            >
              {gateways.map((gateway) => (
                <option key={gateway.id} value={gateway.id}>
                  {gateway.name} ({gateway.uniqueKey}.{gateway.domainName})
                </option>
              ))}
            </select>
          </label>
          <div className="grid grid-cols-3 gap-3">
            <label className={fieldClass}>
              <span className={labelClass}>Method</span>
              <select
                value={form.httpMethod}
                onChange={(e) => setForm({ ...form, httpMethod: e.target.value })}
                className={inputClass}
              >
                {HTTP_METHODS.map((method) => (
                  <option key={method} value={method}>
                    {method}
                  </option>
                ))}
              </select>
            </label>
            <label className={`${fieldClass} col-span-2`}>
              <span className={labelClass}>Path</span>
              <input
                type="text"
                required
                placeholder="/orders"
                value={form.path}
                onChange={(e) => setForm({ ...form, path: e.target.value })}
                className={`${inputClass} font-mono`}
              />
            </label>
          </div>
          <label className={fieldClass}>
            <span className={labelClass}>Priority</span>
            <input
              type="number"
              value={form.priority}
              onChange={(e) => setForm({ ...form, priority: e.target.value })}
              className={inputClass}
            />
          </label>
          <div className="flex gap-2 sm:col-span-2">
            <Button type="submit" variant="primary" disabled={busy}>
              Create flow
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
              <th className="px-4 py-3 font-medium">Route</th>
              <th className="px-4 py-3 font-medium">Gateway</th>
              <th className="px-4 py-3 font-medium">Active version</th>
              <th className="px-4 py-3 text-right font-medium">Actions</th>
            </tr>
          </thead>
          <tbody>
            {!flows && (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-muted">
                  Loading…
                </td>
              </tr>
            )}
            {flows?.items.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-muted">
                  No flows yet.
                </td>
              </tr>
            )}
            {flows?.items.map((flow) => (
              <tr key={flow.id} className="border-b border-border last:border-0 hover:bg-surface-hover">
                <td className="px-4 py-3">
                  <Link href={`/flows/${flow.id}`} className="font-medium text-foreground hover:text-cyan-600 dark:hover:text-cyan-400">
                    {flow.name}
                  </Link>
                  <p className="font-mono text-xs text-muted">{flow.flowKey}</p>
                </td>
                <td className="px-4 py-3 font-mono text-xs text-muted">
                  <span className="text-cyan-600 dark:text-cyan-400">{flow.httpMethod}</span> {flow.path}
                </td>
                <td className="px-4 py-3 text-muted">{flow.gatewayName}</td>
                <td className="px-4 py-3">
                  {flow.activeFlowVersionStatus ? (
                    <StatusBadge status={flow.activeFlowVersionStatus} />
                  ) : (
                    <span className="text-xs text-muted">No adopted version</span>
                  )}
                </td>
                <td className="px-4 py-3 text-right">
                  <div className="flex justify-end gap-2">
                    <Link href={`/flows/${flow.id}`} className={buttonClasses("secondary", "sm")}>
                      Open
                    </Link>
                    <Button variant="danger" size="icon" title="Delete" onClick={() => handleDelete(flow)}>
                      <TrashIcon className="h-4 w-4" />
                    </Button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Panel>

      {flows && (
        <Pagination
          page={flows.page}
          totalPages={flows.totalPages}
          totalElements={flows.totalElements}
          onChange={setPage}
        />
      )}
    </div>
  );
}
