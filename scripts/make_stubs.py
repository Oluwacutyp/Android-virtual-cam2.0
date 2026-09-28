#!/usr/bin/env python3
"""r52a: generate the four stub ONNX models (0 MB data mandate).

Committed artifacts live in app/src/debug/assets/stubs/ (debug source set
only — never packaged in a release APK). Contracts:

  stub_w600k_r50.onnx      IN  input.1  ["None",3,112,112] fp32
                           OUT 683       [1,512]             fp32
  stub_inswapper_128.onnx  IN  target    [1,3,128,128]       fp32
                               source    [1,512]             fp32
                           OUT output    [1,3,128,128]       fp32
                           output = target*0.5 + mean(source)  — depends on
                           BOTH inputs (catches a silently ignored 'source')
  probe_fp16_io.onnx       fp16 tensors end to end
  probe_fp16_inner.onnx    fp32 I/O + fp16 weights (the inswapper pattern)
"""
import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

OUT = "app/src/debug/assets/stubs"


def save(model, name):
    onnx.checker.check_model(model)
    path = f"{OUT}/{name}"
    onnx.save(model, path)
    import os
    print(f"{name}: {os.path.getsize(path)} bytes")


def w600k():
    W = np.arange(3 * 512, dtype=np.float32).reshape(3, 512) / 10000.0
    node_rm = helper.make_node("ReduceMean", ["input.1"], ["pooled"],
                               axes=[2, 3], keepdims=0)
    node_mm = helper.make_node("MatMul", ["pooled", "W"], ["logits"])
    node_rs = helper.make_node("Reshape", ["logits", "shape512"], ["683"])
    graph = helper.make_graph(
        [node_rm, node_mm, node_rs],
        "stub_w600k",
        [helper.make_tensor_value_info("input.1", TensorProto.FLOAT,
                                       ["None", 3, 112, 112])],
        [helper.make_tensor_value_info("683", TensorProto.FLOAT, [1, 512])],
        [numpy_helper.from_array(W, name="W"),
         numpy_helper.from_array(np.array([1, 512], dtype=np.int64),
                                 name="shape512")],
    )
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)])
    model.ir_version = 8
    return model


def inswapper():
    mean = helper.make_node("ReduceMean", ["source"], ["s"],
                            axes=[0, 1], keepdims=1)
    half = helper.make_node("Mul", ["target", "half"], ["halved"])
    add = helper.make_node("Add", ["halved", "s"], ["output"])
    graph = helper.make_graph(
        [mean, half, add],
        "stub_inswapper",
        [helper.make_tensor_value_info("target", TensorProto.FLOAT,
                                       [1, 3, 128, 128]),
         helper.make_tensor_value_info("source", TensorProto.FLOAT,
                                       [1, 512])],
        [helper.make_tensor_value_info("output", TensorProto.FLOAT,
                                       [1, 3, 128, 128])],
        [numpy_helper.from_array(np.array([0.5], dtype=np.float32),
                                 name="half")],
    )
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)])
    model.ir_version = 8
    return model


def fp16_io():
    n = helper.make_node("Identity", ["fp16_in"], ["fp16_out"])
    graph = helper.make_graph(
        [n],
        "probe_fp16_io",
        [helper.make_tensor_value_info("fp16_in", TensorProto.FLOAT16, [1, 4])],
        [helper.make_tensor_value_info("fp16_out", TensorProto.FLOAT16, [1, 4])],
    )
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)])
    model.ir_version = 8
    return model


def fp16_inner():
    # fp32 I/O, fp16 weights + internal fp16 compute (the inswapper pattern).
    W16 = (np.arange(4, dtype=np.float32) + 1.0).astype(np.float16).reshape(1, 4)
    c1 = helper.make_node("Cast", ["x"], ["x16"], to=TensorProto.FLOAT16)
    mul = helper.make_node("Mul", ["x16", "W16"], ["y16"])
    c2 = helper.make_node("Cast", ["y16"], ["y"], to=TensorProto.FLOAT)
    graph = helper.make_graph(
        [c1, mul, c2],
        "probe_fp16_inner",
        [helper.make_tensor_value_info("x", TensorProto.FLOAT, [1, 4])],
        [helper.make_tensor_value_info("y", TensorProto.FLOAT, [1, 4])],
        [numpy_helper.from_array(W16, name="W16")],
    )
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)])
    model.ir_version = 8
    return model


if __name__ == "__main__":
    save(w600k(), "stub_w600k_r50.onnx")
    save(inswapper(), "stub_inswapper_128.onnx")
    save(fp16_io(), "probe_fp16_io.onnx")
    save(fp16_inner(), "probe_fp16_inner.onnx")
    print("total:",
          sum(os.path.getsize(f"{OUT}/{f}") for f in os.listdir(OUT)
              if f.endswith(".onnx")) if False else
          __import__("os").path.getsize(f"{OUT}/stub_w600k_r50.onnx")
          + __import__("os").path.getsize(f"{OUT}/stub_inswapper_128.onnx")
          + __import__("os").path.getsize(f"{OUT}/probe_fp16_io.onnx")
          + __import__("os").path.getsize(f"{OUT}/probe_fp16_inner.onnx"),
          "bytes")
