using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;
using PKHeX.Core;

namespace ThorVault.Engine.Core;

public sealed record CompatibilityReport(
    bool Ok,
    bool Compatible,
    object Destination,
    object Pokemon,
    IReadOnlyList<string> Blockers,
    IReadOnlyList<string> Warnings);

public static class EngineCore
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = false,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };


    private static SaveFile LoadSupportedSave(string path)
    {
        if (!File.Exists(path))
            throw new FileNotFoundException("The uploaded save could not be found.", path);

        return LoadSupportedSave(File.ReadAllBytes(path), path);
    }

    private static SaveFile LoadSupportedSave(byte[] saveBytes, string filename)
    {
        if (!SaveUtil.IsSizeValid(saveBytes.Length))
            throw new InvalidDataException($"Unsupported save size: {saveBytes.Length} bytes.");

        // PKHeX.Core 26.7.7 exposes SaveUtil.GetSaveFile(path), while the
        // byte-array GetVariantSAV API is not available in the package used by
        // ThorVault. Materialize the bytes temporarily and use the same
        // supported parser as the existing server/CLI path.
        var safeName = string.IsNullOrWhiteSpace(filename) ? "main" : Path.GetFileName(filename);
        var tempPath = Path.Combine(Path.GetTempPath(), $"{Guid.NewGuid():N}_{safeName}");

        File.WriteAllBytes(tempPath, saveBytes);
        try
        {
            var parsed = SaveUtil.GetSaveFile(tempPath);
            return parsed switch
            {
                SAV3RS rubySapphire => rubySapphire,
                SAV3E emerald => emerald,
                SAV3FRLG fireRedLeafGreen => fireRedLeafGreen,
                SAV4DP diamondPearl => diamondPearl,
                SAV4Pt platinum => platinum,
                SAV4HGSS heartGoldSoulSilver => heartGoldSoulSilver,
                SAV5BW blackWhite => blackWhite,
                SAV5B2W2 blackWhite2 => blackWhite2,
                SAV6XY xy => xy,
                SAV6AO ao => ao,
                SAV7SM sm => sm,
                _ => throw new InvalidDataException(
                    "This build accepts Pokémon Ruby, Sapphire, Emerald, FireRed, LeafGreen, " +
                    "Diamond, Pearl, Platinum, HeartGold, SoulSilver, Black, White, Black 2, White 2, " +
                    "X, Y, Omega Ruby, Alpha Sapphire, " +
                    "Sun, Moon, Ultra Sun, and Ultra Moon saves. " +
                    $"Detected: {parsed?.GetType().Name ?? "unknown"}."),
            };
        }
        finally
        {
            try { File.Delete(tempPath); }
            catch { }
        }
    }

    private static string GetGameName(SaveFile save) => save.Version switch
    {
        GameVersion.R => "Pokémon Ruby",
        GameVersion.S => "Pokémon Sapphire",
        GameVersion.RS => "Pokémon Ruby/Sapphire",
        GameVersion.E => "Pokémon Emerald",
        GameVersion.FR => "Pokémon FireRed",
        GameVersion.LG => "Pokémon LeafGreen",
        GameVersion.FRLG => "Pokémon FireRed/LeafGreen",
        GameVersion.D => "Pokémon Diamond",
        GameVersion.P => "Pokémon Pearl",
        GameVersion.Pt => "Pokémon Platinum",
        GameVersion.HG => "Pokémon HeartGold",
        GameVersion.SS => "Pokémon SoulSilver",
        GameVersion.B => "Pokémon Black",
        GameVersion.W => "Pokémon White",
        GameVersion.B2 => "Pokémon Black 2",
        GameVersion.W2 => "Pokémon White 2",
        GameVersion.X => "Pokémon X",
        GameVersion.Y => "Pokémon Y",
        GameVersion.OR => "Pokémon Omega Ruby",
        GameVersion.AS => "Pokémon Alpha Sapphire",
        GameVersion.SN => "Pokémon Sun",
        GameVersion.MN => "Pokémon Moon",
        GameVersion.US => "Pokémon Ultra Sun",
        GameVersion.UM => "Pokémon Ultra Moon",
        _ => save.Version.ToString(),
    };

    private static string GetGameFamily(SaveFile save) => save switch
    {
        SAV3RS => "RS",
        SAV3E => "E",
        SAV3FRLG => "FRLG",
        SAV4DP => "DP",
        SAV4Pt => "PT",
        SAV4HGSS => "HGSS",
        SAV5BW => "BW",
        SAV5B2W2 => "B2W2",
        SAV6XY => "XY",
        SAV6AO => "ORAS",
        SAV7SM => "SM",
        SAV7USUM => "USUM",
        _ => $"GEN{save.Generation}",
    };

    private static string GetBoxName(SaveFile save, int box) => save switch
    {
        SAV6XY xy => xy.GetBoxName(box),
        SAV6AO ao => ao.GetBoxName(box),
        SAV7SM sm => sm.GetBoxName(box),
        SAV7USUM usum => usum.GetBoxName(box),
        _ => $"Box {box + 1}",
    };

    private static string NormalizeFormat(string format)
    {
        var value = format.Trim().TrimStart('.').ToLowerInvariant();
        return value is "pk3" or "pk4" or "pk5" or "pk6" or "pk7"
            ? value
            : throw new InvalidDataException($"Unsupported Pokémon payload format: {format}.");
    }

    private static int GenerationForFormat(string format) => NormalizeFormat(format) switch
    {
        "pk3" => 3,
        "pk4" => 4,
        "pk5" => 5,
        "pk6" => 6,
        "pk7" => 7,
        _ => throw new InvalidDataException("Unsupported Pokémon payload format."),
    };

    private static PKM LoadPokemon(string path, string format)
    {
        if (!File.Exists(path))
            throw new FileNotFoundException("The stored Pokémon could not be found.", path);

        var payload = File.ReadAllBytes(path);
        PKM pokemon = NormalizeFormat(format) switch
        {
            "pk3" => new PK3(payload),
            "pk4" => new PK4(payload),
            "pk5" => new PK5(payload),
            "pk6" => new PK6(payload),
            "pk7" => new PK7(payload),
            _ => throw new InvalidDataException("Unsupported Pokémon payload format."),
        };

        if (pokemon.Species == 0)
            throw new InvalidDataException($"The stored {NormalizeFormat(format).ToUpperInvariant()} payload does not contain a Pokémon.");

        return pokemon;
    }

    private static (bool Valid, bool Parsed) AnalyzeLegality(PKM pokemon, SaveFile save)
    {
        try
        {
            var legality = new LegalityAnalysis(pokemon);
            return (legality.Valid, legality.Parsed);
        }
        catch
        {
            return (false, false);
        }
    }

    public static object ImportSave(string path)
    {
        var saveBytes = File.ReadAllBytes(path);
        return ImportSaveBytes(saveBytes, Path.GetFileName(path));
    }


    public static object CheckCompatibilityBytes(
        byte[] saveBytes,
        byte[] pokemonBytes,
        string filename,
        string format)
    {
        var saveTemp = Path.Combine(Path.GetTempPath(), $"{Guid.NewGuid():N}_{Path.GetFileName(filename ?? "main")}");
        var pokemonTemp = Path.Combine(Path.GetTempPath(), $"{Guid.NewGuid():N}_pokemon.{(format ?? "pk6").TrimStart('.')}");
        File.WriteAllBytes(saveTemp, saveBytes);
        File.WriteAllBytes(pokemonTemp, pokemonBytes);
        try
        {
            return CheckCompatibility(saveTemp, pokemonTemp, format);
        }
        finally
        {
            try { File.Delete(saveTemp); } catch { }
            try { File.Delete(pokemonTemp); } catch { }
        }
    }

    public static object WriteSaveBytes(
        byte[] saveBytes,
        byte[] pokemonBytes,
        string filename,
        string format,
        int box,
        int slot)
    {
        var saveTemp = Path.Combine(Path.GetTempPath(), $"{Guid.NewGuid():N}_{Path.GetFileName(filename ?? "main")}");
        var pokemonTemp = Path.Combine(Path.GetTempPath(), $"{Guid.NewGuid():N}_pokemon.{(format ?? "pk6").TrimStart('.')}");
        var outputTemp = Path.Combine(Path.GetTempPath(), $"{Guid.NewGuid():N}_modified_{Path.GetFileName(filename ?? "main")}");
        File.WriteAllBytes(saveTemp, saveBytes);
        File.WriteAllBytes(pokemonTemp, pokemonBytes);
        try
        {
            var result = WriteSave(saveTemp, pokemonTemp, format, box, slot, outputTemp);
            var modified = File.ReadAllBytes(outputTemp);
            return new { result, modifiedSaveBase64 = Convert.ToBase64String(modified) };
        }
        finally
        {
            try { File.Delete(saveTemp); } catch { }
            try { File.Delete(pokemonTemp); } catch { }
            try { File.Delete(outputTemp); } catch { }
        }
    }

    public static object ClearBoxSlotBytes(
        byte[] saveBytes,
        string filename,
        int box,
        int slot,
        string expectedPayloadSha256)
    {
        var saveTemp = Path.Combine(Path.GetTempPath(), $"{Guid.NewGuid():N}_{Path.GetFileName(filename ?? "main")}");
        var outputTemp = Path.Combine(Path.GetTempPath(), $"{Guid.NewGuid():N}_cleared_{Path.GetFileName(filename ?? "main")}");
        File.WriteAllBytes(saveTemp, saveBytes);
        try
        {
            var result = ClearBoxSlot(saveTemp, box, slot, expectedPayloadSha256, outputTemp);
            var modified = File.ReadAllBytes(outputTemp);
            return new { result, modifiedSaveBase64 = Convert.ToBase64String(modified) };
        }
        finally
        {
            try { File.Delete(saveTemp); } catch { }
            try { File.Delete(outputTemp); } catch { }
        }
    }

    public static object ImportSaveBytes(byte[] saveBytes, string filename = "main")
    {
        if (saveBytes is null || saveBytes.Length == 0)
            throw new InvalidDataException("The save data is empty.");

        var save = LoadSupportedSave(saveBytes, filename);
        var records = new List<object>();

        var party = save.PartyData;
        for (var slot = 0; slot < party.Count; slot++)
        {
            var pokemon = party[slot];
            if (pokemon.Species == 0)
                continue;
            records.Add(ToRecord(pokemon, save, "party", null, slot, "Party"));
        }

        var emptySlots = new List<object>();
        for (var box = 0; box < save.BoxCount; box++)
        {
            var boxName = GetBoxName(save, box);
            for (var slot = 0; slot < save.BoxSlotCount; slot++)
            {
                var pokemon = save.GetBoxSlotAtIndex(box, slot);
                if (pokemon.Species == 0)
                {
                    if (!save.IsBoxSlotOverwriteProtected(box, slot))
                    {
                        emptySlots.Add(new
                        {
                            box,
                            slot,
                            displayBox = box + 1,
                            displaySlot = slot + 1,
                            boxName,
                            label = $"{boxName} · slot {slot + 1}",
                        });
                    }
                    continue;
                }

                records.Add(ToRecord(pokemon, save, "box", box, slot, boxName));
            }
        }

        return new
        {
            ok = true,
            readOnly = true,
            nativePayloadFormat = $"pk{save.Generation}",
            save = SaveSummary(save, saveBytes),
            pokemon = records,
            emptySlots,
        };
    }

    public static object InspectPokemon(string pokemonPath, string format)
    {
        var pokemon = LoadPokemon(pokemonPath, format);
        var payload = pokemon.Data[..pokemon.SIZE_STORED].ToArray();
        var payloadHash = Sha256(payload);
        var speciesName = SpeciesName.GetSpeciesNameGeneration(
            pokemon.Species,
            pokemon.Language <= 0 ? (int)LanguageID.English : pokemon.Language,
            pokemon.Format);

        return new
        {
            ok = true,
            pokemon = StandaloneRecord(pokemon, speciesName, payload, payloadHash),
        };
    }


public static object ConvertPk6ToPk7(string savePath, string pokemonPath)
{
    var save = LoadSupportedSave(savePath);
    if (save.Generation != 7)
        throw new InvalidDataException("PK6 → PK7 conversion requires a Generation 7 destination save.");

    var source = LoadPokemon(pokemonPath, "pk6");
    if (source is not PK6)
        throw new InvalidDataException("The source payload is not PK6.");

    var target = new PK7();
    if (!EntityConverter.TryMakePKMCompatible(source, target, out var conversionResult, out var converted))
        throw new InvalidDataException("PKHeX could not convert this PK6 to PK7: " + conversionResult);
    if (converted is not PK7 pk7)
        throw new InvalidDataException($"PKHeX returned {converted.GetType().Name}; PK7 was required.");

    pk7.RefreshChecksum();
    var sourcePayload = source.Data[..source.SIZE_STORED].ToArray();
    var convertedPayload = pk7.Data[..pk7.SIZE_STORED].ToArray();
    var sourceHash = Sha256(sourcePayload);
    var convertedHash = Sha256(convertedPayload);
    var legality = AnalyzeLegality(pk7, save);
    var blockers = new List<string>();
    var warnings = new List<string>();

    if (!pk7.ChecksumValid || !pk7.Valid)
        blockers.Add("The converted PK7 failed checksum or structural validation.");
    if (pk7.Species > save.MaxSpeciesID)
        blockers.Add($"Species ID {pk7.Species} exceeds this destination game's maximum {save.MaxSpeciesID}.");
    if (pk7.Ability > save.MaxAbilityID)
        blockers.Add($"Ability ID {pk7.Ability} exceeds this destination game's maximum {save.MaxAbilityID}.");
    if (pk7.HeldItem > save.MaxItemID)
        blockers.Add($"Held item ID {pk7.HeldItem} exceeds this destination game's maximum {save.MaxItemID}.");
    foreach (var move in new[] { pk7.Move1, pk7.Move2, pk7.Move3, pk7.Move4 }.Where(z => z != 0 && z > save.MaxMoveID))
        blockers.Add($"Move ID {move} exceeds this destination game's maximum {save.MaxMoveID}.");

    if (!legality.Parsed)
        warnings.Add("PKHeX legality analysis could not complete for the converted PK7.");
    else if (!legality.Valid)
        warnings.Add("PKHeX legality analysis reports one or more issues after conversion.");

    var sourceName = SpeciesName.GetSpeciesNameGeneration(source.Species, source.Language <= 0 ? (int)LanguageID.English : source.Language, source.Format);
    var convertedName = SpeciesName.GetSpeciesNameGeneration(pk7.Species, pk7.Language <= 0 ? (int)LanguageID.English : pk7.Language, pk7.Format);

    return new
    {
        ok = true,
        compatible = blockers.Count == 0,
        conversion = new
        {
            direction = "PK6 → PK7",
            engine = "PKHeX.Core EntityConverter.TryMakePKMCompatible",
            result = conversionResult.ToString(),
            sourcePayloadSha256 = sourceHash,
            convertedPayloadSha256 = convertedHash,
            destinationGame = GetGameName(save),
            destinationFamily = GetGameFamily(save),
            destinationGeneration = save.Generation,
            legalityParsed = legality.Parsed,
            legalityValid = legality.Valid,
        },
        source = StandaloneRecord(source, sourceName, sourcePayload, sourceHash),
        converted = StandaloneRecord(pk7, convertedName, convertedPayload, convertedHash),
        changes = BuildConversionChanges(source, pk7),
        blockers,
        warnings,
    };
}

private static IReadOnlyList<object> BuildConversionChanges(PKM source, PKM converted)
{
    var changes = new List<object>();
    void Add(string field, object? before, object? after)
    {
        var left = before?.ToString() ?? "";
        var right = after?.ToString() ?? "";
        changes.Add(new { field, before = left, after = right, changed = left != right });
    }
    Add("Format", $"PK{source.Format}", $"PK{converted.Format}");
    Add("Species", source.Species, converted.Species);
    Add("Nickname", source.Nickname, converted.Nickname);
    Add("Level", source.CurrentLevel, converted.CurrentLevel);
    Add("Nature", source.Nature, converted.Nature);
    Add("Form", source.Form, converted.Form);
    Add("Gender", source.Gender, converted.Gender);
    Add("Shiny", source.IsShiny, converted.IsShiny);
    Add("Held item", source.HeldItem, converted.HeldItem);
    Add("Ability", source.Ability, converted.Ability);
    Add("Ability slot", source.AbilityNumber, converted.AbilityNumber);
    Add("Ball", source.Ball, converted.Ball);
    Add("Friendship", source.CurrentFriendship, converted.CurrentFriendship);
    Add("Origin game", source.Version, converted.Version);
    Add("Original trainer", source.OriginalTrainerName, converted.OriginalTrainerName);
    Add("TID", source.TID16, converted.TID16);
    Add("SID", source.SID16, converted.SID16);
    Add("Move 1", source.Move1, converted.Move1);
    Add("Move 2", source.Move2, converted.Move2);
    Add("Move 3", source.Move3, converted.Move3);
    Add("Move 4", source.Move4, converted.Move4);
    Add("HP IV", source.IV_HP, converted.IV_HP);
    Add("Attack IV", source.IV_ATK, converted.IV_ATK);
    Add("Defense IV", source.IV_DEF, converted.IV_DEF);
    Add("Sp. Atk IV", source.IV_SPA, converted.IV_SPA);
    Add("Sp. Def IV", source.IV_SPD, converted.IV_SPD);
    Add("Speed IV", source.IV_SPE, converted.IV_SPE);
    Add("HP EV", source.EV_HP, converted.EV_HP);
    Add("Attack EV", source.EV_ATK, converted.EV_ATK);
    Add("Defense EV", source.EV_DEF, converted.EV_DEF);
    Add("Sp. Atk EV", source.EV_SPA, converted.EV_SPA);
    Add("Sp. Def EV", source.EV_SPD, converted.EV_SPD);
    Add("Speed EV", source.EV_SPE, converted.EV_SPE);
    return changes;
}

    public static CompatibilityReport CheckCompatibility(
        string savePath,
        string pokemonPath,
        string format)
    {
        var save = LoadSupportedSave(savePath);
        var normalizedFormat = NormalizeFormat(format);
        var pokemon = LoadPokemon(pokemonPath, normalizedFormat);
        var blockers = new List<string>();
        var warnings = new List<string>();
        var expectedGeneration = GenerationForFormat(normalizedFormat);

        if (save.Generation != expectedGeneration)
        {
            blockers.Add(
                $"The destination is Generation {save.Generation}, but the stored entity is {normalizedFormat.ToUpperInvariant()}. " +
                "Cross-generation conversion is not enabled in this release.");
        }
        if (pokemon.Format != expectedGeneration)
            blockers.Add($"The stored entity reports format {pokemon.Format}; {normalizedFormat.ToUpperInvariant()} was required.");
        if (pokemon.Species > save.MaxSpeciesID)
            blockers.Add($"Species ID {pokemon.Species} exceeds this game's maximum {save.MaxSpeciesID}.");
        if (pokemon.Ability > save.MaxAbilityID)
            blockers.Add($"Ability ID {pokemon.Ability} exceeds this game's maximum {save.MaxAbilityID}.");
        if (pokemon.HeldItem > save.MaxItemID)
            blockers.Add($"Held item ID {pokemon.HeldItem} exceeds this game's maximum {save.MaxItemID}.");

        var moves = new[] { pokemon.Move1, pokemon.Move2, pokemon.Move3, pokemon.Move4 };
        foreach (var move in moves.Where(value => value != 0 && value > save.MaxMoveID))
            blockers.Add($"Move ID {move} exceeds this game's maximum {save.MaxMoveID}.");

        var legality = AnalyzeLegality(pokemon, save);
        if (!legality.Parsed)
            warnings.Add("PKHeX legality analysis could not complete for this Pokémon.");
        else if (!legality.Valid)
            warnings.Add("PKHeX legality analysis reports one or more issues. ThorVault will not automatically change the Pokémon.");

        if (!pokemon.ChecksumValid || !pokemon.Valid)
            blockers.Add($"The stored {normalizedFormat.ToUpperInvariant()} failed checksum or structural validation.");

        return new CompatibilityReport(
            Ok: true,
            Compatible: blockers.Count == 0,
            Destination: new
            {
                game = GetGameName(save),
                family = GetGameFamily(save),
                generation = save.Generation,
                requiredPayloadFormat = $"pk{save.Generation}",
                maxSpeciesId = save.MaxSpeciesID,
                maxMoveId = save.MaxMoveID,
                maxItemId = save.MaxItemID,
                maxAbilityId = save.MaxAbilityID,
            },
            Pokemon: new
            {
                speciesId = pokemon.Species,
                species = SpeciesName.GetSpeciesNameGeneration(
                    pokemon.Species,
                    pokemon.Language <= 0 ? (int)LanguageID.English : pokemon.Language,
                    pokemon.Format),
                generation = pokemon.Format,
                format = pokemon.Extension,
                checksumValid = pokemon.ChecksumValid,
                structurallyValid = pokemon.Valid,
                legalityValid = legality.Valid,
                legalityParsed = legality.Parsed,
            },
            Blockers: blockers,
            Warnings: warnings);
    }


public static object ClearBoxSlot(
    string savePath,
    int box,
    int slot,
    string expectedPayloadSha256,
    string outputPath)
{
    var originalBytes = File.ReadAllBytes(savePath);
    var originalHash = Sha256(originalBytes);
    var save = LoadSupportedSave(savePath);
    if (save.Generation < 3 || save.Generation > 7)
        throw new InvalidDataException(
            $"True-move source clearing is not supported for Generation {save.Generation} saves.");

    if ((uint)box >= save.BoxCount)
        throw new ArgumentOutOfRangeException(nameof(box), $"Box must be between 0 and {save.BoxCount - 1}.");
    if ((uint)slot >= save.BoxSlotCount)
        throw new ArgumentOutOfRangeException(nameof(slot), $"Slot must be between 0 and {save.BoxSlotCount - 1}.");
    if (save.IsBoxSlotOverwriteProtected(box, slot))
        throw new InvalidOperationException("The selected source slot is protected and cannot be cleared.");

    var existing = save.GetBoxSlotAtIndex(box, slot);
    if (existing.Species == 0)
        throw new InvalidOperationException("The selected source slot is already empty.");

    var existingPayload = existing.Data[..existing.SIZE_STORED].ToArray();
    var existingPayloadHash = Sha256(existingPayload);
    if (!string.Equals(existingPayloadHash, expectedPayloadSha256, StringComparison.OrdinalIgnoreCase))
    {
        throw new InvalidDataException(
            "The source slot no longer contains the expected Pokémon. " +
            $"Expected {expectedPayloadSha256}, found {existingPayloadHash}.");
    }

    var boxName = GetBoxName(save, box);
    var removedRecord = ToRecord(existing, save, "box", box, slot, boxName);

    // SaveFile.BlankPKM is guaranteed to be the correct entity type for the save.
    save.SetBoxSlotAtIndex(save.BlankPKM, box, slot, EntityImportSettings.None);

    var modifiedBytes = save.Write().ToArray();
    Directory.CreateDirectory(Path.GetDirectoryName(outputPath) ?? ".");
    File.WriteAllBytes(outputPath, modifiedBytes);

    var verification = LoadSupportedSave(outputPath);
    var cleared = verification.GetBoxSlotAtIndex(box, slot);
    if (cleared.Species != 0)
        throw new InvalidDataException("Post-write verification failed: the source slot is not empty.");
    if (!verification.ChecksumsValid)
        throw new InvalidDataException("Post-write verification failed: generated save checksums are invalid.");
    if (verification.Generation != save.Generation)
        throw new InvalidDataException("Post-write verification failed: the save generation changed unexpectedly.");

    return new
    {
        ok = true,
        trueMovePreparation = true,
        originalUntouched = true,
        originalSave = new
        {
            sha256 = originalHash,
            byteLength = originalBytes.Length,
            game = GetGameName(save),
            family = GetGameFamily(save),
            generation = save.Generation,
            trainer = save.OT,
        },
        modifiedSave = new
        {
            sha256 = Sha256(modifiedBytes),
            byteLength = modifiedBytes.Length,
            game = GetGameName(verification),
            family = GetGameFamily(verification),
            generation = verification.Generation,
            trainer = verification.OT,
            checksumsValid = verification.ChecksumsValid,
        },
        source = new
        {
            box,
            slot,
            displayBox = box + 1,
            displaySlot = slot + 1,
            boxName,
            payloadSha256 = existingPayloadHash,
        },
        pokemon = removedRecord,
    };
}

    public static object WriteSave(
        string savePath,
        string pokemonPath,
        string format,
        int box,
        int slot,
        string outputPath)
    {
        var originalBytes = File.ReadAllBytes(savePath);
        var originalHash = Sha256(originalBytes);
        var save = LoadSupportedSave(savePath);
        var normalizedFormat = NormalizeFormat(format);
        var pokemon = LoadPokemon(pokemonPath, normalizedFormat);

        var compatibility = CheckCompatibility(savePath, pokemonPath, normalizedFormat);
        if (!compatibility.Compatible)
            throw new InvalidDataException(
                "Compatibility checks blocked this transfer: " +
                string.Join(" ", compatibility.Blockers));

        if ((uint)box >= save.BoxCount)
            throw new ArgumentOutOfRangeException(nameof(box), $"Box must be between 0 and {save.BoxCount - 1}.");
        if ((uint)slot >= save.BoxSlotCount)
            throw new ArgumentOutOfRangeException(nameof(slot), $"Slot must be between 0 and {save.BoxSlotCount - 1}.");
        if (save.IsBoxSlotOverwriteProtected(box, slot))
            throw new InvalidOperationException("The selected destination slot is protected and cannot be overwritten.");

        var existing = save.GetBoxSlotAtIndex(box, slot);
        if (existing.Species != 0)
            throw new InvalidOperationException(
                $"The selected destination is not empty. It currently contains species ID {existing.Species}.");

        save.SetBoxSlotAtIndex(pokemon, box, slot, EntityImportSettings.None);

        var modifiedBytes = save.Write().ToArray();
        Directory.CreateDirectory(Path.GetDirectoryName(outputPath) ?? ".");
        File.WriteAllBytes(outputPath, modifiedBytes);

        var verification = LoadSupportedSave(outputPath);
        var inserted = verification.GetBoxSlotAtIndex(box, slot);
        if (inserted.Species != pokemon.Species)
            throw new InvalidDataException("Post-write verification failed: the destination slot contains the wrong species.");
        if (!verification.ChecksumsValid)
            throw new InvalidDataException("Post-write verification failed: the generated save checksums are invalid.");
        if (verification.Generation != save.Generation)
            throw new InvalidDataException("Post-write verification failed: the generated save family changed unexpectedly.");

        var expectedPayloadHash = Sha256(pokemon.Data[..pokemon.SIZE_STORED].ToArray());
        var insertedPayloadHash = Sha256(inserted.Data[..inserted.SIZE_STORED].ToArray());
        if (!string.Equals(expectedPayloadHash, insertedPayloadHash, StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException(
                $"Post-write verification failed: the inserted {normalizedFormat.ToUpperInvariant()} payload changed unexpectedly.");

        return new
        {
            ok = true,
            copyOnly = true,
            originalUntouched = true,
            originalSave = new
            {
                sha256 = originalHash,
                byteLength = originalBytes.Length,
                game = GetGameName(save),
                family = GetGameFamily(save),
                generation = save.Generation,
                trainer = save.OT,
            },
            modifiedSave = new
            {
                sha256 = Sha256(modifiedBytes),
                byteLength = modifiedBytes.Length,
                game = GetGameName(verification),
                family = GetGameFamily(verification),
                generation = verification.Generation,
                trainer = verification.OT,
                checksumsValid = verification.ChecksumsValid,
            },
            destination = new
            {
                box,
                slot,
                displayBox = box + 1,
                displaySlot = slot + 1,
                boxName = GetBoxName(verification, box),
            },
            pokemon = ToRecord(inserted, verification, "box", box, slot, GetBoxName(verification, box)),
        };
    }

    private static object SaveSummary(SaveFile save, byte[] saveBytes)
    {
        var party = save.PartyData;
        return new
        {
            type = save.GetType().Name,
            family = GetGameFamily(save),
            generation = save.Generation,
            nativePayloadFormat = $"pk{save.Generation}",
            game = GetGameName(save),
            gameVersion = save.Version.ToString(),
            trainer = save.OT,
            tid = save.TID16,
            sid = save.SID16,
            partyCount = party.Count(pokemon => pokemon.Species != 0),
            pokemonCount = party.Count(pokemon => pokemon.Species != 0) +
                save.BoxData.Count(pokemon => pokemon.Species != 0),
            boxCount = save.BoxCount,
            boxSlotCount = save.BoxSlotCount,
            checksumsValid = save.ChecksumsValid,
            byteLength = saveBytes.Length,
            sha256 = Sha256(saveBytes),
        };
    }

    private static string Sha256(byte[] data) =>
        Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant();

    private static object StandaloneRecord(
        PKM pokemon,
        string speciesName,
        byte[] payload,
        string payloadHash) => new
    {
        speciesId = pokemon.Species,
        species = speciesName,
        nickname = pokemon.Nickname,
        level = pokemon.CurrentLevel,
        gender = pokemon.Gender switch { 0 => "Male", 1 => "Female", _ => "Genderless" },
        shiny = pokemon.IsShiny,
        egg = pokemon.IsEgg,
        form = pokemon.Form,
        nature = pokemon.Nature.ToString(),
        abilityId = pokemon.Ability,
        heldItemId = pokemon.HeldItem,
        ballId = pokemon.Ball,
        friendship = pokemon.CurrentFriendship,
        originGame = pokemon.Version.ToString(),
        originalTrainer = pokemon.OriginalTrainerName,
        tid = pokemon.TID16,
        sid = pokemon.SID16,
        checksumValid = pokemon.ChecksumValid,
        structurallyValid = pokemon.Valid,
        legalityParsed = false,
        generation = pokemon.Format,
        format = pokemon.Extension,
        moves = new[] { pokemon.Move1, pokemon.Move2, pokemon.Move3, pokemon.Move4 },
        ivs = new
        {
            hp = pokemon.IV_HP,
            attack = pokemon.IV_ATK,
            defense = pokemon.IV_DEF,
            specialAttack = pokemon.IV_SPA,
            specialDefense = pokemon.IV_SPD,
            speed = pokemon.IV_SPE,
        },
        evs = new
        {
            hp = pokemon.EV_HP,
            attack = pokemon.EV_ATK,
            defense = pokemon.EV_DEF,
            specialAttack = pokemon.EV_SPA,
            specialDefense = pokemon.EV_SPD,
            speed = pokemon.EV_SPE,
        },
        payloadByteLength = payload.Length,
        payloadSha256 = payloadHash,
        payloadBase64 = Convert.ToBase64String(payload),
    };

    private static object ToRecord(
        PKM pokemon,
        SaveFile save,
        string locationType,
        int? box,
        int slot,
        string locationName)
    {
        var payload = pokemon.Data[..pokemon.SIZE_STORED].ToArray();
        var payloadHash = Sha256(payload);
        var legality = AnalyzeLegality(pokemon, save);
        var speciesName = SpeciesName.GetSpeciesNameGeneration(
            pokemon.Species,
            pokemon.Language <= 0 ? (int)LanguageID.English : pokemon.Language,
            pokemon.Format);

        var standalone = StandaloneRecord(pokemon, speciesName, payload, payloadHash);
        return new
        {
            id = $"{payloadHash}:{locationType}:{box?.ToString() ?? "party"}:{slot}",
            speciesId = pokemon.Species,
            species = speciesName,
            nickname = pokemon.Nickname,
            level = pokemon.CurrentLevel,
            gender = pokemon.Gender switch { 0 => "Male", 1 => "Female", _ => "Genderless" },
            shiny = pokemon.IsShiny,
            egg = pokemon.IsEgg,
            form = pokemon.Form,
            nature = pokemon.Nature.ToString(),
            abilityId = pokemon.Ability,
            heldItemId = pokemon.HeldItem,
            ballId = pokemon.Ball,
            friendship = pokemon.CurrentFriendship,
            originGame = pokemon.Version.ToString(),
            originalTrainer = pokemon.OriginalTrainerName,
            tid = pokemon.TID16,
            sid = pokemon.SID16,
            checksumValid = pokemon.ChecksumValid,
            structurallyValid = pokemon.Valid,
            legalityValid = legality.Valid,
            legalityParsed = legality.Parsed,
            generation = pokemon.Format,
            format = pokemon.Extension,
            moves = new[] { pokemon.Move1, pokemon.Move2, pokemon.Move3, pokemon.Move4 },
            ivs = new
            {
                hp = pokemon.IV_HP,
                attack = pokemon.IV_ATK,
                defense = pokemon.IV_DEF,
                specialAttack = pokemon.IV_SPA,
                specialDefense = pokemon.IV_SPD,
                speed = pokemon.IV_SPE,
            },
            evs = new
            {
                hp = pokemon.EV_HP,
                attack = pokemon.EV_ATK,
                defense = pokemon.EV_DEF,
                specialAttack = pokemon.EV_SPA,
                specialDefense = pokemon.EV_SPD,
                speed = pokemon.EV_SPE,
            },
            payloadByteLength = payload.Length,
            payloadSha256 = payloadHash,
            payloadBase64 = Convert.ToBase64String(payload),
            location = new
            {
                type = locationType,
                box,
                slot,
                displaySlot = slot + 1,
                name = locationName,
            },
        };
    }
}
