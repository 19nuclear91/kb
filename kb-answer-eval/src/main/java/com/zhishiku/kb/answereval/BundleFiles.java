package com.zhishiku.kb.answereval;

import java.nio.file.Path;
import java.util.Map;

/** 完成清单只允许引用当前目录的已绑定文件；残留暂存目录不是完成产物。 */
public final class BundleFiles {
    public record Completion(int schemaVersion, Map<String, DataModel.FileRef> files) {
        public Completion {
            Checks.require(schemaVersion == 1 && files != null && !files.isEmpty());
            files = Map.copyOf(files);
            files.forEach((name, reference) -> Checks.require(name.equals(reference.file())));
        }
    }

    private final Path root;
    private final Completion completion;

    public BundleFiles(Path root) {
        this.root = root;
        completion = StrictJson.read(LocalFiles.read(LocalFiles.child(root, "complete.json"),
                StrictJson.MANIFEST_LIMIT).bytes(), Completion.class);
    }

    public LocalFiles.Frozen read(String file, int limit) {
        DataModel.FileRef reference = completion.files().get(file);
        if (reference == null) {
            throw new EvaluationFailure(EvaluationFailure.Code.INCOMPLETE);
        }
        return LocalFiles.read(root, reference, limit);
    }

    public LocalFiles.Frozen read(DataModel.FileRef reference, int limit) {
        if (!reference.equals(completion.files().get(reference.file()))) {
            throw new EvaluationFailure(EvaluationFailure.Code.BINDING);
        }
        return LocalFiles.read(root, reference, limit);
    }
}
