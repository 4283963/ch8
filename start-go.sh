#!/bin/bash
cd "$(dirname "$0")/go-gateway"
go mod tidy
go run ./cmd
