# custom-flow-upload

A Keycloak 26 extension that restores the **Authentication Flow Import / Export** functionality removed in Keycloak 26. Packaged as a single deployable JAR containing both a REST SPI backend and a custom Admin Console theme.

---

## Why This Exists

Keycloak 26 removed the ability to partially import/export individual authentication flows from the Admin Console. This extension brings that capability back by providing:

- A **REST API** (SPI) to import, export, list, and delete authentication flows programmatically.
- A **custom Admin Console theme** (`flow-management`) that injects **Import Flow** and **Export Flow** buttons directly into the Authentication page — no core Keycloak code modified.

---

## Features

| Feature | Description |
|---------|-------------|
| **Import Flow** | Upload a JSON file to create a complete authentication flow (including sub-flows and authenticator configs) |
| **Export Flow** | Download one or all custom flows as a portable JSON file |
| **List Flows** | View all non-built-in flows in the realm via API |
| **Delete Flow** | Remove a custom flow by alias via API |
| **UI Buttons** | Native-looking Import / Export buttons on the Authentication home page |
| **No Core Edits** | Everything is self-contained in one JAR — safe to deploy alongside any KC version |

---

## Project Structure

```
custom-flow-upload/
├── pom.xml
└── src/
    └── main/
        ├── java/com/example/
        │   ├── FlowUploadProvider.java         # REST resource (all endpoints)
        │   └── FlowUploadProviderFactory.java  # SPI factory (registers provider ID)
        └── resources/
            ├── META-INF/
            │   ├── keycloak-themes.json        # Registers the flow-management theme
            │   └── services/
            │       └── org.keycloak.services.resource.RealmResourceProviderFactory
            └── theme/
                └── flow-management/
                    └── admin/
                        ├── theme.properties    # Extends keycloak.v2, injects JS
                        └── resources/js/
                            └── flow-import-export.js  # UI button injection script
```

---

## REST API Endpoints

All endpoints are scoped to a realm: `/realms/{realm}/flow-uploader/...`

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/realms/{realm}/flow-uploader` | Import flow(s) from JSON body |
| `GET` | `/realms/{realm}/flow-uploader/list` | List all custom (non-built-in) flows |
| `GET` | `/realms/{realm}/flow-uploader/export/{alias}` | Export a single flow by alias |
| `GET` | `/realms/{realm}/flow-uploader/export-all` | Export all custom flows |
| `DELETE` | `/realms/{realm}/flow-uploader/delete/{alias}` | Delete a flow by alias |

### Supported Import JSON Formats

The import endpoint auto-detects and handles all common formats:

**Format A — Export-all / export-single output (wrapper object or array of wrappers):**
```json
[
  {
    "alias": "my-flow",
    "description": "...",
    "providerId": "basic-flow",
    "authenticationFlows": [ ... ],
    "authenticatorConfigs": [ ... ]
  }
]
```

**Format B — Bare flow array (authenticationExecutions directly in item):**
```json
[
  {
    "alias": "my-flow",
    "authenticationExecutions": [ ... ]
  }
]
```

---

## Build

**Prerequisites:** Java 17+, Maven 3.8+

```bash
cd custom-flow-upload
mvn clean package -DskipTests
```

Output: `target/custom-flow-upload-1.0.0.jar`

---

## Deployment

### Step 1 — Copy the JAR to Keycloak's providers folder

```bash
cp target/custom-flow-upload-1.0.0.jar \
   <KEYCLOAK_HOME>/providers/custom-flow-upload-1.0.0.jar
```

For this project, `KEYCLOAK_HOME` is typically:
```
quarkus/dist/target/keycloak-26.6.1
```

### Step 2 — Rebuild Keycloak to register the provider

> **Required after every JAR update.**

```bash
<KEYCLOAK_HOME>/bin/kc.sh build
```

You should see in the output:
```
KC-SERVICES0047: flow-uploader (com.example.FlowUploadProviderFactory) is implementing the internal SPI realm-restapi-extension.
```

### Step 3 — Start Keycloak

```bash
<KEYCLOAK_HOME>/bin/kc.sh start-dev
```

### Step 4 — Activate the Admin Theme (one-time setup)

1. Log in to the Admin Console as `admin`
2. Switch to the **master** realm
3. Go to **Realm Settings → Themes**
4. Set **Admin theme** to `flow-management`
5. Click **Save** and **hard-refresh** the browser (`Ctrl+Shift+R`)

> **Note:** Setting this on the master realm applies to the entire Admin Console across all realms.

---

## Using the UI Buttons

Once the theme is active, navigate to **Authentication** in any realm.

You will see **Import flow** and **Export flow** buttons in the top-right area of the page (below the masthead).

| Button | Behaviour |
|--------|-----------|
| **Import flow** | Opens a file picker. Select a `.json` file. The page reloads after a successful import. |
| **Export flow** | Opens a modal listing all custom flows. Select one or more, then click **Export Selected** to download a `.json` file. |

> The buttons **only appear on the Authentication home page** — they are automatically hidden when you navigate into a specific flow's details.

---

## Export / Import JSON Format

The exported file is a JSON array where each element is a flow wrapper:

```json
[
  {
    "alias": "otp-login-flow",
    "description": "Simple secure login with OTP",
    "providerId": "basic-flow",
    "authenticationFlows": [
      {
        "alias": "otp-login-flow",
        "providerId": "basic-flow",
        "topLevel": true,
        "builtIn": false,
        "authenticationExecutions": [
          {
            "authenticator": "auth-cookie",
            "authenticatorFlow": false,
            "requirement": "ALTERNATIVE",
            "priority": 10
          },
          {
            "authenticatorFlow": true,
            "flowAlias": "otp-login-forms",
            "requirement": "ALTERNATIVE",
            "priority": 20
          }
        ]
      },
      {
        "alias": "otp-login-forms",
        "providerId": "basic-flow",
        "topLevel": false,
        "builtIn": false,
        "authenticationExecutions": [ ... ]
      }
    ],
    "authenticatorConfigs": [
      {
        "alias": "otp-form-config",
        "config": {
          "otpHashAlgorithm": "SHA1",
          "otpTokenLength": "6",
          "otpType": "totp"
        }
      }
    ]
  }
]
```

---

## Quick Deploy Script

For convenience during development, run this after every code change:

```bash
mvn -f /path/to/custom-flow-upload/pom.xml clean package -DskipTests && \
cp target/custom-flow-upload-1.0.0.jar <KEYCLOAK_HOME>/providers/ && \
<KEYCLOAK_HOME>/bin/kc.sh build && \
echo "Ready — restart KC"
```

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `flow-management` not in theme dropdown | Ensure the JAR is in `providers/` and `kc.sh build` was run |
| Buttons don't appear | Confirm `flow-management` is set as Admin theme in the **master** realm; hard-refresh browser |
| Import returns empty flow | Ensure your JSON uses the wrapper format with `authenticationFlows` array inside each element |
| `kc.sh build` fails with "Provider not found" | Check `META-INF/services/org.keycloak.services.resource.RealmResourceProviderFactory` contains exactly `com.example.FlowUploadProviderFactory` with no extra characters |
| API returns 401 | You must be logged in as an admin user; the token is read automatically from `window.keycloak` |

---

## Technical Notes

- **No core Keycloak code is modified.** The extension uses the public `RealmResourceProviderFactory` SPI.
- The Admin Console is a React SPA. The JS uses `setInterval` + `MutationObserver` to detect navigation and injects/removes the buttons accordingly.
- The `META-INF/keycloak-themes.json` file is **required** for Keycloak to discover the bundled theme. Without it, the `theme/` directory inside the JAR is silently ignored.
- `kc.sh build` must be re-run after every JAR update. A simple server restart is not sufficient.
