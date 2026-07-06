#!/usr/bin/env bash
# Talos public Linux x64 uninstall helper.

set -euo pipefail

show_help() {
    cat << EOF
Talos Linux Uninstaller

Usage: bash uninstall-talos.sh [OPTIONS]

Options:
  --install-root <path>  Install root (default: \$HOME/.local/share/talos)
  --bin-dir <path>       User command directory (default: \$HOME/.local/bin)
  --purge                Also remove \$HOME/.talos user data
  --quiet                Do not ask for confirmation
  --help                 Show this help message

Normal uninstall removes the Talos app and command shim only.
Purge also removes Talos config, indices, logs, and Talos-owned caches.
User-owned llama.cpp installs and model files outside .talos are not removed.
EOF
}

INSTALL_ROOT="$HOME/.local/share/talos"
BIN_DIR="$HOME/.local/bin"
PURGE=false
QUIET=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --install-root)
            if [[ $# -lt 2 || -z "${2:-}" ]]; then
                echo "Error: --install-root requires a path"
                exit 1
            fi
            INSTALL_ROOT="$2"
            shift 2
            ;;
        --bin-dir)
            if [[ $# -lt 2 || -z "${2:-}" ]]; then
                echo "Error: --bin-dir requires a path"
                exit 1
            fi
            BIN_DIR="$2"
            shift 2
            ;;
        --purge)
            PURGE=true
            shift
            ;;
        --quiet)
            QUIET=true
            shift
            ;;
        --help)
            show_help
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            show_help
            exit 1
            ;;
    esac
done

USER_DATA="$HOME/.talos"
SHIM="$BIN_DIR/talos"

if [[ "$QUIET" != "true" ]]; then
    echo "Uninstall Talos from:"
    echo "  Install: $INSTALL_ROOT"
    echo "  Command: $SHIM"
    echo "  Remove user data (~/.talos): $PURGE"
    printf "Continue? [y/N] "
    read -r answer || answer=""
    case "$answer" in
        y|Y|yes|YES) ;;
        *) echo "Cancelled."; exit 0 ;;
    esac
fi

echo "- Removing Talos command shim"
if [[ -e "$SHIM" ]]; then
    rm -f "$SHIM"
    echo "  Deleted: $SHIM"
else
    echo "  Command shim not found."
fi

echo "- Removing install directory"
if [[ -e "$INSTALL_ROOT" ]]; then
    rm -rf "$INSTALL_ROOT"
    echo "  Deleted: $INSTALL_ROOT"
else
    echo "  Install directory not found."
fi

if [[ "$PURGE" == "true" ]]; then
    echo "- Removing Talos user data"
    if [[ -e "$USER_DATA" ]]; then
        rm -rf "$USER_DATA"
        echo "  Deleted: $USER_DATA"
    else
        echo "  User data not found."
    fi
else
    echo "  Keeping user data at: $USER_DATA"
fi

echo "Talos uninstall complete."
