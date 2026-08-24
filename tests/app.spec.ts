import { test, expect } from "@playwright/test";

test.describe("Wisperlow App", () => {
  test("completes Windows permission startup without looping", async ({
    page,
  }) => {
    const pageErrors: string[] = [];
    page.on("pageerror", (error) => pageErrors.push(error.message));

    await page.addInitScript(() => {
      const invokeCounts: Record<string, number> = {};
      const callbacks = new Map<number, (...args: unknown[]) => unknown>();
      let nextCallbackId = 1;

      Object.defineProperty(window, "__testInvokeCounts", {
        value: invokeCounts,
      });
      Object.defineProperty(window, "__TAURI_OS_PLUGIN_INTERNALS__", {
        value: { platform: "windows" },
      });
      Object.defineProperty(window, "__TAURI_EVENT_PLUGIN_INTERNALS__", {
        value: {
          unregisterListener: (_event: string, id: number) =>
            callbacks.delete(id),
        },
      });
      Object.defineProperty(window, "__TAURI_INTERNALS__", {
        value: {
          metadata: {
            currentWindow: { label: "main" },
            currentWebview: { windowLabel: "main", label: "main" },
          },
          transformCallback: (callback: (...args: unknown[]) => unknown) => {
            const id = nextCallbackId++;
            callbacks.set(id, callback);
            return id;
          },
          unregisterCallback: (id: number) => callbacks.delete(id),
          invoke: async (command: string) => {
            invokeCounts[command] = (invokeCounts[command] ?? 0) + 1;

            switch (command) {
              case "get_app_settings":
                return { onboarding_completed: false };
              case "get_default_settings":
                return {};
              case "check_custom_sounds":
                return { start: false, stop: false };
              case "get_available_models":
              case "get_available_microphones":
              case "get_available_output_devices":
                return [];
              case "get_current_model":
                return "";
              case "get_windows_microphone_permission_status":
                return { supported: true, overall_access: "allowed" };
              default:
                return null;
            }
          },
        },
      });
    });
    const response = await page.goto("/", { waitUntil: "domcontentloaded" });

    expect(response?.status()).toBe(200);
    await expect(page.getByRole("img", { name: "Wisperlow" })).toBeVisible();
    await expect(
      page.getByText("To get started, choose a transcription model"),
    ).toBeVisible();

    const permissionChecks = await page.evaluate(() => {
      const counts = Reflect.get(window, "__testInvokeCounts") as Record<
        string,
        number
      >;
      return counts.get_windows_microphone_permission_status ?? 0;
    });
    expect(permissionChecks).toBeGreaterThan(0);
    expect(permissionChecks).toBeLessThanOrEqual(2);
    expect(pageErrors).toEqual([]);
  });
});
