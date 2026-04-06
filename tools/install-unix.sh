#!/bin/bash
# Talos Unix/Linux/macOS Installation Script
# Installs Talos to user's local directory and adds to PATH

set -e

show_help() {
    cat << EOF
Talos Unix/Linux/macOS Installer

Usage: bash install-unix.sh [OPTIONS]

Options:
  --force     Reinstall even if already installed
  --sudo      Try to install system-wide to /usr/local (requires sudo)
  --help      Show this help message

Default behavior:
  - Installs to ~/.local/talos
  - Adds ~/.local/talos/bin to PATH via shell profile
EOF
}

FORCE=false
USE_SUDO=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --force)
            FORCE=true
            shift
            ;;
        --sudo)
            USE_SUDO=true
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

# Check if Talos distribution exists
SOURCE_DIR="$(dirname "$0")/../build/install/talos"
if [[ ! -d "$SOURCE_DIR" ]]; then
    echo "Error: Talos distribution not found at $SOURCE_DIR"
    echo "Please run: ./gradlew clean installDist"
    exit 1
fi

# Determine installation directory
if [[ "$USE_SUDO" == "true" ]]; then
    INSTALL_DIR="/usr/local/talos"
    BIN_DIR="/usr/local/bin"
    NEEDS_SUDO=true
else
    INSTALL_DIR="$HOME/.local/talos"
    BIN_DIR="$HOME/.local/talos/bin"
    NEEDS_SUDO=false
    mkdir -p "$HOME/.local"
fi

# Check if already installed
if [[ -d "$INSTALL_DIR" ]] && [[ "$FORCE" != "true" ]]; then
    echo "Talos is already installed at $INSTALL_DIR"
    echo "Use --force to reinstall or run: talos --version"
    exit 0
fi

echo "Installing Talos to $INSTALL_DIR..."

# Remove existing installation if present
if [[ -d "$INSTALL_DIR" ]]; then
    echo "Removing existing installation..."
    if [[ "$NEEDS_SUDO" == "true" ]]; then
        sudo rm -rf "$INSTALL_DIR"
    else
        rm -rf "$INSTALL_DIR"
    fi
fi

# Copy distribution
echo "Copying files..."
if [[ "$NEEDS_SUDO" == "true" ]]; then
    sudo cp -r "$SOURCE_DIR" "$INSTALL_DIR"
    sudo chmod +x "$INSTALL_DIR/bin/talos"
else
    cp -r "$SOURCE_DIR" "$INSTALL_DIR"
    chmod +x "$INSTALL_DIR/bin/talos"
fi

# Handle PATH setup
if [[ "$USE_SUDO" == "true" ]]; then
    # System-wide installation - create symlink
    if [[ ! -f "/usr/local/bin/talos" ]]; then
        echo "Creating symlink in /usr/local/bin..."
        sudo ln -sf "$INSTALL_DIR/bin/talos" "/usr/local/bin/talos"
    fi
else
    # User installation - update shell profile
    SHELL_PROFILE=""

    # Detect shell profile file
    if [[ -n "$ZSH_VERSION" ]] && [[ -f "$HOME/.zshrc" ]]; then
        SHELL_PROFILE="$HOME/.zshrc"
    elif [[ -n "$BASH_VERSION" ]] && [[ -f "$HOME/.bashrc" ]]; then
        SHELL_PROFILE="$HOME/.bashrc"
    elif [[ -f "$HOME/.profile" ]]; then
        SHELL_PROFILE="$HOME/.profile"
    else
        SHELL_PROFILE="$HOME/.bashrc"  # Default fallback
    fi

    # Check if PATH entry already exists
    PATH_ENTRY="export PATH=\"\$HOME/.local/talos/bin:\$PATH\""

    if ! grep -q "\.local/talos/bin" "$SHELL_PROFILE" 2>/dev/null; then
        echo "Adding Talos to PATH in $SHELL_PROFILE..."
        echo "" >> "$SHELL_PROFILE"
        echo "# Added by Talos installer" >> "$SHELL_PROFILE"
        echo "$PATH_ENTRY" >> "$SHELL_PROFILE"
        echo "PATH entry added to $SHELL_PROFILE"
    else
        echo "PATH entry already exists in $SHELL_PROFILE"
    fi
fi

echo ""
echo "✅ Talos installed successfully!"
echo ""
echo "To verify installation:"
if [[ "$USE_SUDO" == "true" ]]; then
    echo "  talos --version"
else
    echo "  1. Open a new terminal window (to reload PATH)"
    echo "  2. Run: talos --version"
    echo ""
    echo "Or source your shell profile now:"
    echo "  source $SHELL_PROFILE"
    echo "  talos --version"
fi
echo ""
echo "To start using Talos:"
echo "  talos                    # Interactive mode"
echo "  talos status             # Check workspace status"
echo "  talos rag-index          # Index current directory"
echo "  talos rag-ask \"question\" # Ask about your code"
