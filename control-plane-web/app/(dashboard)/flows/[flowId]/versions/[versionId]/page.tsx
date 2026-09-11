"use client";

import "@xyflow/react/dist/style.css";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState, type DragEvent, type MouseEvent } from "react";
import {
  ReactFlow,
  ReactFlowProvider,
  Background,
  BackgroundVariant,
  Controls,
  Handle,
  Position,
  useNodesState,
  useEdgesState,
  type Node,
  type Edge,
  type NodeProps,
} from "@xyflow/react";
import { StatusBadge } from "@/components/StatusBadge";
import { Button } from "@/components/Button";
import { inputClass, labelClass, fieldClass } from "@/components/Input";
import {
  ArrowLeftIcon,
  ChevronRightIcon,
  XIcon,
  TrashIcon,
  PlayIcon,
  ArchiveIcon,
  WorkflowIcon,
  ExternalLinkIcon,
} from "@/components/icons";
import { api, ApiError } from "@/lib/api";
import type { FlowResponse, FlowStepComponentType, FlowStepResponse, FlowVersionResponse } from "@/lib/types";

const NODE_WIDTH = 260;
const ROW_HEIGHT = 150;

const COMPONENT_META: Record<FlowStepComponentType, { badge: string; dot: string; label: string }> = {
  FUNCTION: {
    badge: "bg-cyan-100 text-cyan-700 dark:bg-cyan-500/10 dark:text-cyan-400",
    dot: "bg-cyan-500",
    label: "Function",
  },
  RESPONSE: {
    badge: "bg-violet-100 text-violet-700 dark:bg-violet-500/10 dark:text-violet-400",
    dot: "bg-violet-500",
    label: "Response",
  },
};

const PALETTE_ITEMS: { type: FlowStepComponentType; title: string; description: string }[] = [
  { type: "FUNCTION", title: "Function", description: "Runs a pinned Node artifact" },
  { type: "RESPONSE", title: "Response", description: "Ends the flow with the last result" },
];

interface StepNodeData extends Record<string, unknown> {
  step: FlowStepResponse;
  selected: boolean;
}

function StepNode({ data }: NodeProps<Node<StepNodeData>>) {
  const { step, selected } = data;
  const meta = COMPONENT_META[step.componentType];
  return (
    <div
      style={{ width: NODE_WIDTH }}
      className={`cursor-pointer rounded-xl border bg-surface px-4 py-3 shadow-sm transition-colors ${
        selected ? "border-cyan-500 ring-2 ring-cyan-500/30" : "border-border hover:border-border-strong"
      }`}
    >
      <Handle type="target" position={Position.Top} isConnectable={false} className="!bg-border-strong" />
      <div className="flex items-center gap-2">
        <span className={`h-2 w-2 shrink-0 rounded-full ${meta.dot}`} />
        <span className="truncate text-sm font-medium text-foreground">{step.stepKey}</span>
      </div>
      <div className="mt-2 flex items-center justify-between">
        <span className={`rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide ${meta.badge}`}>
          {meta.label}
        </span>
        <span className="font-mono text-[11px] text-muted">#{step.position}</span>
      </div>
      <Handle type="source" position={Position.Bottom} isConnectable={false} className="!bg-border-strong" />
    </div>
  );
}

const nodeTypes = { step: StepNode };

type InspectorMode = { kind: "view" | "edit"; step: FlowStepResponse } | { kind: "create"; componentType: FlowStepComponentType } | null;

export default function FlowVersionEditorPage() {
  return (
    <ReactFlowProvider>
      <FlowVersionCanvas />
    </ReactFlowProvider>
  );
}

function FlowVersionCanvas() {
  const params = useParams<{ flowId: string; versionId: string }>();
  const router = useRouter();
  const { flowId, versionId } = params;

  const [flow, setFlow] = useState<FlowResponse | null>(null);
  const [version, setVersion] = useState<FlowVersionResponse | null>(null);
  const [versionList, setVersionList] = useState<FlowVersionResponse[]>([]);
  const [reloadKey, setReloadKey] = useState(0);
  const [inspector, setInspector] = useState<InspectorMode>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [nodes, setNodes, onNodesChange] = useNodesState<Node<StepNodeData>>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const flowData = await api.getFlow(flowId);
        if (!cancelled) setFlow(flowData);
      } catch {
        if (!cancelled) setError("Failed to load flow");
      }
      try {
        const versionData = await api.getFlowVersion(flowId, versionId);
        if (!cancelled) setVersion(versionData);
      } catch {
        if (!cancelled) setError("Failed to load version");
      }
      try {
        const versions = await api.listFlowVersions(flowId, 1, 50);
        if (!cancelled) setVersionList(versions.items.sort((a, b) => b.version - a.version));
      } catch {
        if (!cancelled) setVersionList([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [flowId, versionId, reloadKey]);

  const isDraft = version?.status === "DRAFT";
  const steps = useMemo(
    () => [...(version?.steps ?? [])].sort((a, b) => a.position - b.position),
    [version]
  );

  useEffect(() => {
    const selectedId = inspector?.kind !== "create" ? inspector?.step.id : undefined;
    const nextNodes: Node<StepNodeData>[] = steps.map((step, index) => ({
      id: step.id,
      type: "step",
      position: { x: 0, y: index * ROW_HEIGHT },
      data: { step, selected: step.id === selectedId },
      draggable: false,
    }));
    const nextEdges: Edge[] = steps.slice(1).map((step, index) => ({
      id: `${steps[index].id}-${step.id}`,
      source: steps[index].id,
      target: step.id,
      animated: true,
      style: { stroke: "var(--color-border-strong)", strokeWidth: 2 },
    }));
    setNodes(nextNodes);
    setEdges(nextEdges);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [steps, inspector]);

  function refresh() {
    setReloadKey((key) => key + 1);
  }

  const onNodeClick = useCallback(
    (_: MouseEvent, node: Node<StepNodeData>) => {
      setInspector({ kind: isDraft ? "edit" : "view", step: node.data.step });
    },
    [isDraft]
  );

  function onPaletteDragStart(event: DragEvent, type: FlowStepComponentType) {
    event.dataTransfer.setData("application/funchole-step", type);
    event.dataTransfer.effectAllowed = "move";
  }

  function onDragOver(event: DragEvent) {
    event.preventDefault();
    event.dataTransfer.dropEffect = "move";
  }

  function onDrop(event: DragEvent) {
    event.preventDefault();
    if (!isDraft) return;
    const type = event.dataTransfer.getData("application/funchole-step") as FlowStepComponentType;
    if (!type) return;
    setInspector({ kind: "create", componentType: type });
  }

  async function handleAdopt() {
    setError(null);
    setBusy(true);
    try {
      await api.adoptFlowVersion(flowId, versionId);
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to adopt version");
    } finally {
      setBusy(false);
    }
  }

  async function handleArchive() {
    setError(null);
    setBusy(true);
    try {
      await api.archiveFlowVersion(flowId, versionId);
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to archive version");
    } finally {
      setBusy(false);
    }
  }

  if (!flow || !version) {
    return <p className="p-6 text-sm text-muted">Loading…</p>;
  }

  return (
    <div className="flex h-[calc(100vh-3.5rem)] flex-col">
      <div className="flex items-center justify-between border-b border-border bg-surface px-6 py-3">
        <div className="flex items-center gap-3">
          <Link
            href={`/flows/${flowId}`}
            className="flex h-8 w-8 items-center justify-center rounded-lg border border-border text-muted hover:bg-surface-hover hover:text-foreground"
          >
            <ArrowLeftIcon className="h-4 w-4" />
          </Link>
          <nav className="flex items-center gap-1.5 text-sm text-muted">
            <Link href="/flows" className="hover:text-foreground">
              Flows
            </Link>
            <ChevronRightIcon className="h-3.5 w-3.5" />
            <Link href={`/flows/${flowId}`} className="font-medium text-foreground hover:text-cyan-600 dark:hover:text-cyan-400">
              {flow.name}
            </Link>
          </nav>
          <select
            value={versionId}
            onChange={(e) => router.push(`/flows/${flowId}/versions/${e.target.value}`)}
            className="h-8 rounded-lg border border-border bg-surface px-2 font-mono text-sm text-foreground outline-none"
          >
            {versionList.map((v) => (
              <option key={v.id} value={v.id}>
                v{v.version}
              </option>
            ))}
          </select>
          <StatusBadge status={version.status} />
        </div>
        <div className="flex items-center gap-2">
          <span
            title={`${flow.httpMethod} ${flow.path} on ${flow.gatewayName}`}
            className="hidden items-center gap-1 font-mono text-xs text-muted sm:flex"
          >
            <ExternalLinkIcon className="h-3.5 w-3.5" />
            {flow.httpMethod} {flow.path}
          </span>
          {version.status === "DRAFT" && (
            <Button
              variant="primary"
              onClick={handleAdopt}
              disabled={busy || steps.length === 0}
              title={steps.length === 0 ? "Add at least one step first" : "Make this the live version"}
            >
              <PlayIcon className="h-4 w-4" />
              Adopt
            </Button>
          )}
          {version.status === "ADOPTED" && (
            <Button variant="secondary" onClick={handleArchive} disabled={busy}>
              <ArchiveIcon className="h-4 w-4" />
              Archive
            </Button>
          )}
        </div>
      </div>

      {error && (
        <p role="alert" className="border-b border-rose-200 bg-rose-50 px-6 py-2 text-sm text-rose-600 dark:border-rose-900/40 dark:bg-rose-500/10 dark:text-rose-400">
          {error}
        </p>
      )}

      <div className="flex flex-1 overflow-hidden">
        <aside className="flex w-64 flex-col gap-4 overflow-y-auto border-r border-border bg-surface p-4">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wide text-muted">Add node</p>
            <p className="mt-1 text-xs text-muted">
              {isDraft ? "Drag onto the canvas to add a step." : "Only DRAFT versions can be edited."}
            </p>
          </div>
          <div className="flex flex-col gap-2">
            {PALETTE_ITEMS.map((item) => (
              <div
                key={item.type}
                draggable={isDraft}
                onDragStart={(e) => onPaletteDragStart(e, item.type)}
                onClick={() => isDraft && setInspector({ kind: "create", componentType: item.type })}
                className={`rounded-lg border border-border bg-background px-3 py-2.5 transition-colors ${
                  isDraft ? "cursor-grab hover:border-cyan-500/60 active:cursor-grabbing" : "cursor-not-allowed opacity-50"
                }`}
              >
                <div className="flex items-center gap-2">
                  <span className={`h-2 w-2 rounded-full ${COMPONENT_META[item.type].dot}`} />
                  <span className="text-sm font-medium text-foreground">{item.title}</span>
                </div>
                <p className="mt-0.5 text-xs text-muted">{item.description}</p>
              </div>
            ))}
          </div>
          <div className="mt-auto rounded-lg border border-border bg-background p-3">
            <div className="flex items-center gap-2 text-xs text-muted">
              <WorkflowIcon className="h-3.5 w-3.5" />
              {steps.length} step{steps.length === 1 ? "" : "s"}
            </div>
          </div>
        </aside>

        <div className="relative flex-1" onDragOver={onDragOver} onDrop={onDrop}>
          {steps.length === 0 ? (
            <div className="flex h-full flex-col items-center justify-center gap-2 text-center">
              <WorkflowIcon className="h-8 w-8 text-muted" />
              <p className="text-sm text-muted">
                {isDraft ? "Drag Function or Response from the left onto the canvas." : "This version has no steps."}
              </p>
            </div>
          ) : (
            <ReactFlow
              nodes={nodes}
              edges={edges}
              onNodesChange={onNodesChange}
              onEdgesChange={onEdgesChange}
              onNodeClick={onNodeClick}
              nodeTypes={nodeTypes}
              nodesDraggable={false}
              nodesConnectable={false}
              elementsSelectable
              fitView
              fitViewOptions={{ padding: 0.3 }}
              colorMode="system"
              proOptions={{ hideAttribution: true }}
            >
              <Background variant={BackgroundVariant.Dots} gap={20} size={1} />
              <Controls showInteractive={false} />
            </ReactFlow>
          )}
        </div>

        {inspector && (
          <StepInspector
            mode={inspector}
            flowId={flowId}
            versionId={versionId}
            nextPosition={steps.length > 0 ? Math.max(...steps.map((s) => s.position)) + 10 : 10}
            isDraft={isDraft}
            onClose={() => setInspector(null)}
            onSaved={() => {
              setInspector(null);
              refresh();
            }}
            onError={setError}
          />
        )}
      </div>
    </div>
  );
}

interface StepInspectorProps {
  mode: NonNullable<InspectorMode>;
  flowId: string;
  versionId: string;
  nextPosition: number;
  isDraft: boolean;
  onClose: () => void;
  onSaved: () => void;
  onError: (message: string) => void;
}

function StepInspector({ mode, flowId, versionId, nextPosition, isDraft, onClose, onSaved, onError }: StepInspectorProps) {
  const isCreate = mode.kind === "create";
  const initialStep = mode.kind !== "create" ? mode.step : null;

  const [stepKey, setStepKey] = useState(initialStep?.stepKey ?? "");
  const [componentType, setComponentType] = useState<FlowStepComponentType>(
    initialStep?.componentType ?? (mode.kind === "create" ? mode.componentType : "FUNCTION")
  );
  const [position, setPosition] = useState(String(initialStep?.position ?? nextPosition));
  const [componentId, setComponentId] = useState(initialStep?.componentId ?? "");
  const [componentVersionId, setComponentVersionId] = useState(initialStep?.componentVersionId ?? "");
  const [busy, setBusy] = useState(false);

  const editable = isDraft && mode.kind !== "view";

  async function handleSave() {
    setBusy(true);
    const payload = {
      stepKey: stepKey.trim(),
      componentType,
      position: Number(position),
      componentId: componentId.trim(),
      componentVersionId: componentVersionId.trim(),
    };
    try {
      if (isCreate) {
        await api.createFlowStep(flowId, versionId, payload);
      } else if (initialStep) {
        await api.updateFlowStep(flowId, versionId, initialStep.id, payload);
      }
      onSaved();
    } catch (err) {
      onError(err instanceof ApiError ? err.message : "Failed to save step");
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete() {
    if (!initialStep || !window.confirm(`Remove step "${initialStep.stepKey}"?`)) return;
    setBusy(true);
    try {
      await api.deleteFlowStep(flowId, versionId, initialStep.id);
      onSaved();
    } catch (err) {
      onError(err instanceof ApiError ? err.message : "Failed to delete step");
    } finally {
      setBusy(false);
    }
  }

  return (
    <aside className="flex w-80 flex-col border-l border-border bg-surface">
      <div className="flex items-center justify-between border-b border-border px-4 py-3">
        <div>
          <p className="text-sm font-semibold text-foreground">
            {isCreate ? "New step" : initialStep?.stepKey}
          </p>
          <p className="text-xs text-muted">{isCreate ? COMPONENT_META[componentType].label : mode.kind === "view" ? "Read only" : "Edit step"}</p>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close"
          className="flex h-7 w-7 cursor-pointer items-center justify-center rounded-lg text-muted hover:bg-surface-hover hover:text-foreground"
        >
          <XIcon className="h-4 w-4" />
        </button>
      </div>

      <div className="flex flex-1 flex-col gap-4 overflow-y-auto p-4">
        <label className={fieldClass}>
          <span className={labelClass}>Step key</span>
          <input
            type="text"
            required
            maxLength={150}
            disabled={!editable}
            placeholder="list-orders"
            value={stepKey}
            onChange={(e) => setStepKey(e.target.value)}
            className={inputClass}
          />
        </label>

        {isCreate && (
          <label className={fieldClass}>
            <span className={labelClass}>Component type</span>
            <select
              value={componentType}
              onChange={(e) => setComponentType(e.target.value as FlowStepComponentType)}
              className={inputClass}
            >
              {PALETTE_ITEMS.map((item) => (
                <option key={item.type} value={item.type}>
                  {item.title}
                </option>
              ))}
            </select>
          </label>
        )}

        <label className={fieldClass}>
          <span className={labelClass}>Order</span>
          <input
            type="number"
            required
            disabled={!editable}
            value={position}
            onChange={(e) => setPosition(e.target.value)}
            className={inputClass}
          />
        </label>

        <label className={fieldClass}>
          <span className={labelClass}>Component ID</span>
          <input
            type="text"
            required
            disabled={!editable}
            placeholder="88888888-8888-8888-8888-888888888861"
            value={componentId}
            onChange={(e) => setComponentId(e.target.value)}
            className={`${inputClass} font-mono text-xs`}
          />
        </label>

        <label className={fieldClass}>
          <span className={labelClass}>Component version ID</span>
          <input
            type="text"
            required
            disabled={!editable}
            placeholder="99999999-9999-9999-9999-999999999861"
            value={componentVersionId}
            onChange={(e) => setComponentVersionId(e.target.value)}
            className={`${inputClass} font-mono text-xs`}
          />
        </label>
      </div>

      {editable && (
        <div className="flex gap-2 border-t border-border p-4">
          <Button variant="primary" className="flex-1" disabled={busy} onClick={handleSave}>
            {isCreate ? "Create step" : "Save changes"}
          </Button>
          {!isCreate && (
            <Button variant="danger" size="icon" disabled={busy} onClick={handleDelete}>
              <TrashIcon className="h-4 w-4" />
            </Button>
          )}
        </div>
      )}
    </aside>
  );
}
