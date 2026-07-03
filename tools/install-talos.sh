#!/usr/bin/env bash
# Talos public Linux x64 bootstrap installer.
# Installs Talos from a runtime-bundled release tarball, verifies SHA-256, then
# hands off to the explicit Talos setup wizard.

set -euo pipefail

show_help() {
    cat << EOF
Talos Linux Installer

Usage: bash install-talos.sh [OPTIONS]

Options:
  --repository <owner/repo>   GitHub repository (default: ai21z/talos-assistant)
  --version <version>         Release version or latest (default: latest)
  --install-root <path>       Install root (default: \$HOME/.local/share/talos)
  --bin-dir <path>            User command directory (default: \$HOME/.local/bin)
  --profile-file <path>       Write the PATH entry to an explicit shell profile
  --artifact-file <path>      Install a locally staged tarball instead of downloading
  --checksums-file <path>     Verify against a locally staged checksums.txt
  --force                     Replace an existing install
  --no-wizard                 Do not launch talos setup wizard after install
  --help                      Show this help message

Default behavior:
  - Supports Linux x64 only
  - Installs under the current user's home directory
  - Verifies checksums.txt before extraction or install
  - Adds the command directory to the user's shell profile
  - Runs talos setup wizard after Talos itself is installed
EOF
}

REPOSITORY="ai21z/talos-assistant"
VERSION="latest"
INSTALL_ROOT="$HOME/.local/share/talos"
BIN_DIR="$HOME/.local/bin"
EXPLICIT_PROFILE_FILE=""
ARTIFACT_FILE=""
CHECKSUMS_FILE=""
FORCE=false
NO_WIZARD=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --repository)
            if [[ $# -lt 2 || -z "${2:-}" ]]; then
                echo "Error: --repository requires a value"
                exit 1
            fi
            REPOSITORY="$2"
            shift 2
            ;;
        --version)
            if [[ $# -lt 2 || -z "${2:-}" ]]; then
                echo "Error: --version requires a value"
                exit 1
            fi
            VERSION="$2"
            shift 2
            ;;
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
        --profile-file)
            if [[ $# -lt 2 || -z "${2:-}" ]]; then
                echo "Error: --profile-file requires a path"
                exit 1
            fi
            EXPLICIT_PROFILE_FILE="$2"
            shift 2
            ;;
        --artifact-file)
            if [[ $# -lt 2 || -z "${2:-}" ]]; then
                echo "Error: --artifact-file requires a path"
                exit 1
            fi
            ARTIFACT_FILE="$2"
            shift 2
            ;;
        --checksums-file)
            if [[ $# -lt 2 || -z "${2:-}" ]]; then
                echo "Error: --checksums-file requires a path"
                exit 1
            fi
            CHECKSUMS_FILE="$2"
            shift 2
            ;;
        --force)
            FORCE=true
            shift
            ;;
        --no-wizard)
            NO_WIZARD=true
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

assert_supported_linux_x64() {
    local os_name
    local os_arch
    os_name="$(uname -s 2>/dev/null || true)"
    os_arch="$(uname -m 2>/dev/null || true)"
    if [[ "$os_name" != "Linux" || ( "$os_arch" != "x86_64" && "$os_arch" != "amd64" ) ]]; then
        echo "Unsupported OS/arch: ${os_name:-unknown}/${os_arch:-unknown}. Talos public Linux installer supports Linux x64 only."
        exit 2
    fi
}

require_command() {
    local name="$1"
    if ! command -v "$name" >/dev/null 2>&1; then
        echo "Required command not found: $name"
        exit 2
    fi
}

resolve_latest_version() {
    local metadata
    metadata="$(curl -fsSL -H "User-Agent: talos-installer" "https://api.github.com/repos/$REPOSITORY/releases/latest")"
    local tag
    tag="$(printf '%s\n' "$metadata" | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)"
    if [[ -z "$tag" ]]; then
        echo "Could not resolve latest Talos release version from GitHub."
        exit 1
    fi
    echo "${tag#v}"
}

release_tag_for_version() {
    local version="$1"
    if [[ "$version" == v* ]]; then
        echo "$version"
    else
        echo "v$version"
    fi
}

read_expected_sha256() {
    local checksum_file="$1"
    local file_name="$2"
    local expected
    expected="$(awk -v name="$file_name" '{
        if ($2 == name || $2 == "*" name) {
            print tolower($1)
            found = 1
            exit
        }
    } END { if (!found) exit 1 }' "$checksum_file" || true)"
    if [[ -z "$expected" ]]; then
        echo "No SHA256 entry for $file_name in checksums.txt"
        exit 1
    fi
    echo "$expected"
}

assert_sha256() {
    local file_path="$1"
    local expected="$2"
    local actual
    actual="$(sha256sum "$file_path" | awk '{print tolower($1)}')"
    if [[ "$actual" != "$expected" ]]; then
        echo "Checksum mismatch for $file_path. Expected $expected, got $actual."
        exit 1
    fi
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
        echo "fish_add_path $BIN_DIR"
    else
        echo "export PATH=\"$BIN_DIR:\$PATH\""
    fi
}

add_path_entry() {
    local profile="$1"
    local entry
    entry="$(path_entry_for_profile "$profile")"

    mkdir -p "$(dirname "$profile")"
    touch "$profile"
    if ! grep -Fq "$BIN_DIR" "$profile" 2>/dev/null; then
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

prepare_release_inputs() {
    local temp_root="$1"
    local artifact_path="$temp_root/artifact.tar.gz"
    local checksum_path="$temp_root/checksums.txt"
    local release_version="$VERSION"
    local artifact_name=""

    if [[ -n "$ARTIFACT_FILE" || -n "$CHECKSUMS_FILE" ]]; then
        if [[ -z "$ARTIFACT_FILE" || -z "$CHECKSUMS_FILE" ]]; then
            echo "--artifact-file and --checksums-file must be provided together."
            exit 1
        fi
        if [[ ! -f "$ARTIFACT_FILE" ]]; then
            echo "Artifact file not found: $ARTIFACT_FILE"
            exit 1
        fi
        if [[ ! -f "$CHECKSUMS_FILE" ]]; then
            echo "Checksums file not found: $CHECKSUMS_FILE"
            exit 1
        fi
        artifact_name="$(basename "$ARTIFACT_FILE")"
        cp "$ARTIFACT_FILE" "$artifact_path"
        cp "$CHECKSUMS_FILE" "$checksum_path"
    else
        require_command curl
        if [[ "$release_version" == "latest" ]]; then
            release_version="$(resolve_latest_version)"
        else
            release_version="${release_version#v}"
        fi
        artifact_name="talos-$release_version-linux-x64-app.tar.gz"
        local tag
        tag="$(release_tag_for_version "$release_version")"
        local base_url="https://github.com/$REPOSITORY/releases/download/$tag"
        curl -fL --retry 3 -o "$artifact_path" "$base_url/$artifact_name"
        curl -fL --retry 3 -o "$checksum_path" "$base_url/checksums.txt"
    fi

    local expected
    expected="$(read_expected_sha256 "$checksum_path" "$artifact_name")"
    assert_sha256 "$artifact_path" "$expected"
    printf '%s\n%s\n' "$artifact_path" "$artifact_name"
}

install_app_image() {
    local artifact_path="$1"
    local temp_root="$2"
    local extract_root="$temp_root/extract"
    mkdir -p "$extract_root"
    tar -xzf "$artifact_path" -C "$extract_root"

    local launcher
    launcher="$(find "$extract_root" -type f -path "*/bin/talos" | head -n 1)"
    if [[ -z "$launcher" ]]; then
        echo "No talos launcher found in release artifact."
        exit 1
    fi
    chmod +x "$launcher"

    local app_source
    app_source="$(cd "$(dirname "$launcher")/.." && pwd)"
    local app_target="$INSTALL_ROOT/app"

    if [[ -e "$INSTALL_ROOT" ]]; then
        if [[ "$FORCE" != "true" ]]; then
            echo "Install target already exists: $INSTALL_ROOT. Rerun with --force to replace it."
            exit 1
        fi
        rm -rf "$INSTALL_ROOT"
    fi

    mkdir -p "$app_target" "$BIN_DIR"
    cp -a "$app_source/." "$app_target/"

    cat > "$BIN_DIR/talos" << EOF
#!/usr/bin/env sh
exec "$app_target/bin/talos" "\$@"
EOF
    chmod +x "$BIN_DIR/talos"
}

assert_supported_linux_x64
require_command tar
require_command sha256sum
require_command awk
require_command sed

TEMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEMP_ROOT"' EXIT

mapfile -t release_inputs < <(prepare_release_inputs "$TEMP_ROOT")
ARTIFACT_PATH="${release_inputs[0]}"
ARTIFACT_NAME="${release_inputs[1]}"

install_app_image "$ARTIFACT_PATH" "$TEMP_ROOT"
PROFILE_FILE="$(select_shell_profile)"
add_path_entry "$PROFILE_FILE"

echo "Installed Talos from $ARTIFACT_NAME to $INSTALL_ROOT"
echo "Verifying installed Talos..."
"$BIN_DIR/talos" --version

resolved="$(command -v talos 2>/dev/null || true)"
if [[ -n "$resolved" && "$resolved" != "$BIN_DIR/talos" ]]; then
    echo "Warning: current PATH resolves talos to $resolved"
    echo "Open a new shell or source $PROFILE_FILE so $BIN_DIR appears first."
fi

if [[ "$NO_WIZARD" == "true" ]]; then
    echo "Next step:"
    echo "  talos setup wizard"
else
    echo "Starting Talos setup wizard..."
    "$BIN_DIR/talos" setup wizard
fi
