#!/bin/bash

echo "Checking server pod status..."
kubectl get pods -n jervis -l app=jervis-server

echo ""
echo "Waiting for new pod to be ready..."
kubectl wait --for=condition=ready pod -l app=jervis-server -n jervis --timeout=60s

echo ""
echo "Checking latest logs for A2A connections..."
kubectl logs -n jervis -l app=jervis-server --tail=50 | grep -E "A2A|agent-card|Failed to connect" || echo "No A2A-related logs yet"
