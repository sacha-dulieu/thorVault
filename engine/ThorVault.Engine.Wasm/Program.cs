using ThorVault.Engine.Core;
using System.Runtime.InteropServices.JavaScript;
using System.Text.Json;

namespace ThorVault.Engine.Wasm;

public partial class LocalEngine
{
    public static void Main() { }

    [JSExport]
    public static string ImportSave(string base64, string filename)
    {
        try
        {
            var bytes = Convert.FromBase64String(base64);
            var result = EngineCore.ImportSaveBytes(bytes, filename);
            return JsonSerializer.Serialize(result);
        }
        catch (Exception ex)
        {
            return JsonSerializer.Serialize(new
            {
                ok = false,
                error = ex.Message,
                errorType = ex.GetType().Name,
                detail = ex.ToString(),
            });
        }
    }

    [JSExport]
    public static string CheckCompatibility(
        string saveBase64, string pokemonBase64, string filename, string format)
    {
        try
        {
            var result = EngineCore.CheckCompatibilityBytes(
                Convert.FromBase64String(saveBase64),
                Convert.FromBase64String(pokemonBase64),
                filename,
                format);
            return JsonSerializer.Serialize(new { ok = true, result });
        }
        catch (Exception ex)
        {
            return JsonSerializer.Serialize(new {
                ok = false, error = ex.Message, errorType = ex.GetType().Name, detail = ex.ToString()
            });
        }
    }

    [JSExport]
    public static string WriteSave(
        string saveBase64, string pokemonBase64, string filename,
        string format, int box, int slot)
    {
        try
        {
            var result = EngineCore.WriteSaveBytes(
                Convert.FromBase64String(saveBase64),
                Convert.FromBase64String(pokemonBase64),
                filename, format, box, slot);
            return JsonSerializer.Serialize(new { ok = true, result });
        }
        catch (Exception ex)
        {
            return JsonSerializer.Serialize(new {
                ok = false, error = ex.Message, errorType = ex.GetType().Name, detail = ex.ToString()
            });
        }
    }

    [JSExport]
    public static string ClearBoxSlot(
        string saveBase64, string filename, int box, int slot, string expectedPayloadSha256)
    {
        try
        {
            var result = EngineCore.ClearBoxSlotBytes(
                Convert.FromBase64String(saveBase64),
                filename, box, slot, expectedPayloadSha256);
            return JsonSerializer.Serialize(new { ok = true, result });
        }
        catch (Exception ex)
        {
            return JsonSerializer.Serialize(new {
                ok = false, error = ex.Message, errorType = ex.GetType().Name, detail = ex.ToString()
            });
        }
    }

    [JSExport]
    public static string GetEngineInfo()
    {
        return JsonSerializer.Serialize(new
        {
            ok = true,
            engine = "ThorVault.Engine.Wasm",
            generationRange = "3-7",
            nativeFormats = new[] { "pk3", "pk4", "pk5", "pk6", "pk7" },
        });
    }
}
