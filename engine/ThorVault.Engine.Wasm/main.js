import { dotnet } from "./dotnet.js";

const { getAssemblyExports, getConfig, runMain } =
  await dotnet.withApplicationArguments("start").create();

const config = getConfig();
const exports = await getAssemblyExports(config.mainAssemblyName);

const localEngine =
  exports?.ThorVault?.Engine?.Wasm?.LocalEngine;

if (!localEngine || typeof localEngine.ImportSave !== "function") {
  throw new Error(
    "ThorVault local engine started, but LocalEngine JS exports were not found."
  );
}

globalThis.ThorVaultLocalEngine = localEngine;

await runMain();

export default localEngine;
