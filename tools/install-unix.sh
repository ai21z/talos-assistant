#!/bin/bash
# Talos Unix/Linux/macOS source/developer installer.
# Installs the Gradle distribution and prepares PATH. Model runtimes and
# model weights remain explicit post-install setup steps.

set -euo pipefail

show_help() {
    cat << EOF
Talos Unix/Linux/macOS Installer

Usage: bash install-unix.sh [OPTIONS]

Options:
  --force                  Reinstall even if already installed
  --sudo                   Try to install system-wide to /usr/local (requires sudo)
  --dry-run                Show bootstrap decisions without copying files, writing PATH, launching Talos, or installing packages
  --profile-file <path>    Write the PATH entry to an explicit shell profile
  --allow-package-install  Allow the installer to run the rendered Java package-manager command
  --help                   Show this help message

Default behavior:
  - Installs to ~/.local/talos
  - Adds ~/.local/talos/bin to PATH via the user's login-shell profile
  - Prints Java package-manager guidance, but does not install packages unless --allow-package-install is passed
  - Never downloads llama.cpp or model weights
EOF
}

FORCE=false
USE_SUDO=false
DRY_RUN=false
ALLOW_PACKAGE_INSTALL=false
EXPLICIT_PROFILE_FILE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --force)
            FORCE=true
            shift
            ;;
        --sudo)
            USE_SUDO=true
            shift
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --profile-file)
            if [[ $# -lt 2 || -z "${2:-}" ]]; then
                echo "Error: --profile-file requires a path"
                exit 1
            fi
            EXPLICIT_PROFILE_FILE="$2"
            shift 2
            ;;
        --allow-package-install)
            ALLOW_PACKAGE_INSTALL=true
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

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="$SCRIPT_DIR/../build/install/talos"

if [[ "$USE_SUDO" == "true" ]]; then
    INSTALL_DIR="/usr/local/talos"
    BIN_DIR="/usr/local/bin"
    NEEDS_SUDO=true
else
    INSTALL_DIR="$HOME/.local/talos"
    BIN_DIR="$HOME/.local/talos/bin"
    NEEDS_SUDO=false
fi

detect_distro_id() {
    if [[ -r /etc/os-release ]]; then
        . /etc/os-release
        echo "${ID:-unknown}:${ID_LIKE:-}"
    else
        echo "unknown:"
    fi
}

java_install_command() {
    local distro
    distro="$(detect_distro_id)"
    case "$distro" in
        ubuntu:*|debian:*|*:debian*|*:ubuntu*)
            echo "sudo apt update && sudo apt install -y openjdk-21-jre-headless"
            ;;
        *)
            echo ""
            ;;
    esac
}

detect_java_feature() {
    if ! command -v java >/dev/null 2>&1; then
        echo 0
        return
    fi

    local version
    version="$(java -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')"
    if [[ "$version" =~ ^1\.([0-9]+)\. ]]; then
        echo "${BASH_REMATCH[1]}"
    elif [[ "$version" =~ ^([0-9]+) ]]; then
        echo "${BASH_REMATCH[1]}"
    else
        echo 0
    fi
}

print_java_guidance() {
    local command_line
    command_line="$(java_install_command)"
    echo "Java 21+ was not detected. Talos source/developer install cannot launch before Java is available."
    if [[ -n "$command_line" ]]; then
        echo "Install Java 21 on this Ubuntu/Debian-like system with:"
        echo "  $command_line"
    else
        echo "Install Java 21+ with your platform package manager, then rerun this installer."
    fi
}

ensure_java_runtime() {
    local feature
    feature="$(detect_java_feature)"
    if [[ "$feature" -ge 21 ]]; then
        echo "Java $feature detected."
        return 0
    fi

    print_java_guidance
    if [[ "$ALLOW_PACKAGE_INSTALL" != "true" ]]; then
        echo "Package-manager execution skipped. Re-run with --allow-package-install only if you want this script to run the command above."
        return 2
    fi

    local command_line
    command_line="$(java_install_command)"
    if [[ -z "$command_line" ]]; then
        echo "No safe package-manager command is known for this platform."
        return 2
    fi

    echo "Running package-manager command after explicit --allow-package-install:"
    echo "  $command_line"
    sudo apt update
    sudo apt install -y openjdk-21-jre-headless

    feature="$(detect_java_feature)"
    if [[ "$feature" -ge 21 ]]; then
        echo "Java $feature detected after package install."
        return 0
    fi

    echo "Java 21+ still was not detected after package install."
    return 2
}

login_shell_name() {
    local shell_path=""
    if [[ -n "${SHELL:-}" ]]; then
        shell_path="$SHELL"
    elif command -v getent >/dev/null 2>&1; then
        shell_path="$(getent passwd "$(id -un)" | awk -F: '{print $7}')"
    fi
    basename "${shell_path:-sh}"
}

select_shell_profile() {
    if [[ -n "$EXPLICIT_PROFILE_FILE" ]]; then
        echo "$EXPLICIT_PROFILE_FILE"
        return
    fi

    local shell_name
    shell_name="$(login_shell_name)"
    case "$shell_name" in
        zsh)
            echo "$HOME/.zshrc"
            ;;
        bash)
            echo "$HOME/.bashrc"
            ;;
        fish)
            echo "$HOME/.config/fish/config.fish"
            ;;
        sh|dash|ksh)
            echo "$HOME/.profile"
            ;;
        *)
            if [[ -f "$HOME/.profile" ]]; then
                echo "$HOME/.profile"
            else
                echo "$HOME/.bashrc"
            fi
            ;;
    esac
}

path_entry_for_profile() {
    local profile="$1"
    if [[ "$profile" == */config.fish ]]; then
        echo "fish_add_path \$HOME/.local/talos/bin"
    else
        echo "export PATH=\"\$HOME/.local/talos/bin:\$PATH\""
    fi
}

add_path_entry() {
    local profile="$1"
    local entry
    entry="$(path_entry_for_profile "$profile")"

    if [[ "$DRY_RUN" == "true" ]]; then
        echo "Would ensure PATH entry in $profile:"
        echo "  $entry"
        return
    fi

    mkdir -p "$(dirname "$profile")"
    touch "$profile"
    if ! grep -q "\.local/talos/bin" "$profile" 2>/dev/null; then
        echo "Adding Talos to PATH in $profile..."
        {
            echo ""
            echo "# Added by Talos installer"
            echo "$entry"
        } >> "$profile"
        echo "PATH entry added to $profile"
    else
        echo "PATH entry already exists in $profile"
    fi
}

verify_installed_talos() {
    if [[ "$DRY_RUN" == "true" ]]; then
        echo "Would verify direct installed binary:"
        echo "  \"$INSTALL_DIR/bin/talos\" --version"
        return 0
    fi

    echo "Verifying direct installed Talos binary..."
    "$INSTALL_DIR/bin/talos" --version

    local resolved=""
    resolved="$(command -v talos 2>/dev/null || true)"
    if [[ -n "$resolved" && "$resolved" != "$INSTALL_DIR/bin/talos" ]]; then
        echo "Warning: current PATH resolves talos to $resolved"
        echo "Open a new shell or source the selected profile so $INSTALL_DIR/bin appears first."
    fi
}

if [[ "$DRY_RUN" == "true" ]]; then
    echo "Talos Unix bootstrap dry run"
    echo "No changes will be made: no file copies, no package installs, no PATH writes, no Talos JVM launch, no llama.cpp downloads, no model downloads."
    echo "Install dir: $INSTALL_DIR"
    echo "Bin dir: $BIN_DIR"
    if [[ "$USE_SUDO" != "true" ]]; then
        echo "Shell profile: $(select_shell_profile)"
    fi
    echo "Java feature: $(detect_java_feature)"
    if [[ "$(detect_java_feature)" -lt 21 ]]; then
        print_java_guidance
    fi
    echo "Distribution source: $SOURCE_DIR"
    if [[ -d "$SOURCE_DIR" ]]; then
        echo "Distribution source: found"
    else
        echo "Distribution source: missing; run ./gradlew clean installDist --no-daemon"
    fi
    verify_installed_talos
    echo "Next after successful install: talos setup wizard"
    exit 0
fi

if ! ensure_java_runtime; then
    exit 2
fi

if [[ ! -d "$SOURCE_DIR" ]]; then
    echo "Error: Talos distribution not found at $SOURCE_DIR"
    echo "Please run: ./gradlew clean installDist --no-daemon"
    exit 1
fi

if [[ "$USE_SUDO" != "true" ]]; then
    mkdir -p "$HOME/.local"
fi

if [[ -d "$INSTALL_DIR" && "$FORCE" != "true" ]]; then
    echo "Talos is already installed at $INSTALL_DIR"
    echo "Use --force to reinstall or run the direct installed binary:"
    echo "  \"$INSTALL_DIR/bin/talos\" --version"
    exit 0
fi

echo "Installing Talos to $INSTALL_DIR..."

if [[ -d "$INSTALL_DIR" ]]; then
    echo "Removing existing installation..."
    if [[ "$NEEDS_SUDO" == "true" ]]; then
        sudo rm -rf "$INSTALL_DIR"
    else
        rm -rf "$INSTALL_DIR"
    fi
fi

echo "Copying files..."
if [[ "$NEEDS_SUDO" == "true" ]]; then
    sudo cp -r "$SOURCE_DIR" "$INSTALL_DIR"
    sudo chmod +x "$INSTALL_DIR/bin/talos"
else
    cp -r "$SOURCE_DIR" "$INSTALL_DIR"
    chmod +x "$INSTALL_DIR/bin/talos"
fi

SHELL_PROFILE=""
if [[ "$USE_SUDO" == "true" ]]; then
    if [[ ! -f "/usr/local/bin/talos" ]]; then
        echo "Creating symlink in /usr/local/bin..."
        sudo ln -sf "$INSTALL_DIR/bin/talos" "/usr/local/bin/talos"
    fi
else
    SHELL_PROFILE="$(select_shell_profile)"
    add_path_entry "$SHELL_PROFILE"
fi

echo ""
echo "Talos installed successfully."
echo ""
verify_installed_talos
echo ""
echo "To start using Talos:"
if [[ "$USE_SUDO" != "true" ]]; then
    echo "  source $SHELL_PROFILE"
fi
echo "  talos setup wizard        # guided model config path"
echo "  talos status --verbose    # inspect local runtime state"
echo "  talos                     # interactive mode"
