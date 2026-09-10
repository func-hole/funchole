#!/usr/bin/env node
// Persistent Node Executor.
//
// Reads newline-delimited JSON EXECUTE messages from stdin and writes
// newline-delimited JSON RESULT/ERROR messages to stdout. This process is
// started once by the Java Runtime Worker (PersistentNodeExecutor) and stays
// alive across many artifact executions - it never exits after a single
// request.
//
// This script knows nothing about Flow, JetStream, Gateway, or the FuncHole
// database. It receives an artifactPath and input, and executes.

import { createInterface } from "node:readline";
import { existsSync } from "node:fs";
import { pathToFileURL } from "node:url";

const rl = createInterface({ input: process.stdin, terminal: false });

function writeMessage(message) {
  process.stdout.write(JSON.stringify(message) + "\n");
}

function sendError(executionId, code, message) {
  writeMessage({ type: "ERROR", executionId, error: { code, message } });
}

async function handleExecute(message) {
  const { executionId, artifactPath, input } = message;

  if (!artifactPath || !existsSync(artifactPath)) {
    sendError(executionId, "ARTIFACT_NOT_FOUND", `Artifact not found: ${artifactPath}`);
    return;
  }

  let loadedModule;
  try {
    loadedModule = await import(pathToFileURL(artifactPath).href);
  } catch (error) {
    sendError(executionId, "ARTIFACT_LOAD_ERROR", error && error.message ? error.message : String(error));
    return;
  }

  const handler = loadedModule.handler;
  if (typeof handler !== "function") {
    sendError(executionId, "HANDLER_NOT_FOUND", `Artifact does not export a 'handler' function: ${artifactPath}`);
    return;
  }

  let parsedInput;
  try {
    parsedInput = input === undefined || input === null || input === "" ? undefined : JSON.parse(input);
  } catch (error) {
    sendError(executionId, "ARTIFACT_EXECUTION_ERROR", `Failed to parse input JSON: ${error.message}`);
    return;
  }

  let output;
  try {
    output = await handler(parsedInput);
  } catch (error) {
    sendError(executionId, "ARTIFACT_EXECUTION_ERROR", error && error.message ? error.message : String(error));
    return;
  }

  let serializedOutput;
  try {
    serializedOutput = JSON.stringify(output);
  } catch (error) {
    sendError(executionId, "OUTPUT_SERIALIZATION_ERROR", error && error.message ? error.message : String(error));
    return;
  }

  writeMessage({ type: "RESULT", executionId, output: serializedOutput });
}

rl.on("line", (line) => {
  let message;
  try {
    message = JSON.parse(line);
  } catch (error) {
    console.error(`Failed to parse EXECUTE line: ${error.message}`);
    return;
  }

  if (message.type !== "EXECUTE") {
    console.error(`Unsupported message type: ${message.type}`);
    return;
  }

  // Deliberately not awaited here: concurrent EXECUTE requests may overlap.
  // Node's stdout write queue keeps each JSON line intact regardless of
  // interleaving, so no additional locking is required on this side.
  handleExecute(message).catch((error) => {
    console.error(`Unhandled error executing ${message.executionId}: ${error && error.message ? error.message : error}`);
  });
});

console.error("Node executor ready");
