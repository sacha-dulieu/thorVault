using System.Text.Json;
using ThorVault.Engine.Core;
namespace ThorVault.Engine;
public static class Program
{
    public static int Main(string[] args)
    {
        try
        {
            object result=args switch
            {
                ["import-save",var p]=>EngineCore.ImportSave(p),
                ["import-oras",var p]=>EngineCore.ImportSave(p),
                ["import-gen6",var p]=>EngineCore.ImportSave(p),
                ["check-save",var s,var p,var f]=>EngineCore.CheckCompatibility(s,p,f),
                ["inspect-pokemon",var p,var f]=>EngineCore.InspectPokemon(p,f),
                ["convert-pk6-to-pk7",var s,var p]=>EngineCore.ConvertPk6ToPk7(s,p),
                ["clear-box-slot",var s,var b,var sl,var h,var o]=>EngineCore.ClearBoxSlot(s,int.Parse(b),int.Parse(sl),h,o),
                ["write-save",var s,var p,var f,var b,var sl,var o]=>EngineCore.WriteSave(s,p,f,int.Parse(b),int.Parse(sl),o),
                _=>throw new ArgumentException("Unsupported ThorVault.Engine command.")
            };
            Console.WriteLine(JsonSerializer.Serialize(result));
            return 0;
        }
        catch(Exception ex)
        {
            Console.Error.WriteLine(ex.Message);
            Console.WriteLine(JsonSerializer.Serialize(new {ok=false,error=ex.Message,errorType=ex.GetType().Name}));
            return 1;
        }
    }
}
