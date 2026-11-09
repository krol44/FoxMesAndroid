package org.telegram.messenger.foxmes;

import com.google.gson.JsonObject;
import org.telegram.messenger.foxmes.FoxMesModels.DeleteResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

final class FoxMesDeleteOperations {
    private static String key(long chatId, List<Integer> ids) {
        return "delete_operation:" + chatId + ":" + new TreeSet<>(ids);
    }

    static DeleteResult execute(FoxMesRuntime runtime, long chatId, List<Integer> ids) throws IOException {
        String key = key(chatId, ids);
        String operation = runtime.store().get(key);
        boolean resolve = operation != null;
        if (operation == null) {
            operation = FoxMesJson.operationId();
            runtime.store().put(key, operation);
        }
        try {
            DeleteResult result;
            if (resolve) {
                JsonObject journal = runtime.api.deleteOperation(operation);
                String status = FoxMesJson.optString(journal, "status");
                if ("done".equals(status)) {
                    result = FoxMesJson.parse(journal.get("result"), DeleteResult.class);
                } else if ("unknown".equals(status)) {
                    result = runtime.api.batchDeleteMessages(chatId, new ArrayList<>(new TreeSet<>(ids)), operation);
                } else {
                    throw new IOException("invalid deletion operation state");
                }
            } else {
                result = runtime.api.batchDeleteMessages(chatId, new ArrayList<>(new TreeSet<>(ids)), operation);
            }
            validate(result, ids);
            return result;
        } catch (FoxMesHttpException error) {
            if (!error.isTransient()) {
                runtime.store().put(key, null);
            }
            throw error;
        }
    }

    static String operation(FoxMesRuntime runtime, long chatId, List<Integer> ids) {
        return runtime.store().get(key(chatId, ids));
    }

    static void complete(FoxMesRuntime runtime, long chatId, List<Integer> ids, String operation) {
        String key = key(chatId, ids);
        synchronized (runtime.store()) {
            if (operation != null && operation.equals(runtime.store().get(key))) {
                runtime.store().put(key, null);
            }
        }
    }

    private static void validate(DeleteResult result, List<Integer> ids) throws IOException {
        if (result == null || result.deletedIds == null
                || result.skippedPinnedIds == null || result.rejectedIds == null) {
            throw new IOException("incomplete deletion result");
        }
        Set<Integer> requested = new HashSet<>(ids);
        Set<Integer> answered = new HashSet<>();
        for (List<Integer> group : java.util.Arrays.asList(
                result.deletedIds, result.skippedPinnedIds, result.rejectedIds)) {
            for (Integer id : group) {
                if (!requested.contains(id) || !answered.add(id)) {
                    throw new IOException("invalid deletion result");
                }
            }
        }
        if (!answered.equals(requested)) {
            throw new IOException("incomplete deletion result");
        }
    }
}
