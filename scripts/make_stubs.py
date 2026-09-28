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
  probe_fp16_conv.onnx     fp32 I/O -> fp16 CONV (+bias, Relu) -> fp32 out —
                           r54-D: only a Conv can fail the way the real fp16
                           inswapper fails; Identity/Mul probes are worthless
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


def fp16_conv():
    """r54-D: the ONLY probe that can fail like the real fp16 model fails.

    fp32 in [1,3,16,16] -> Cast to FLOAT16 -> Conv (FLOAT16 weights
    [4,3,3,3] + bias [4]) -> Relu -> Cast back to FLOAT -> [1,4,14,14].
    Identity/Mul probes cannot catch an fp16-Conv failure; this one can.
    """
    W16 = (((np.arange(4 * 3 * 3 * 3, dtype=np.float32) % 7) - 3) / 7.0).astype(np.float16).reshape(4, 3, 3, 3)
    B16 = np.array([0.1, -0.1, 0.2, -0.2], dtype=np.float16)
    c1 = helper.make_node("Cast", ["x"], ["x16"], to=TensorProto.FLOAT16)
    conv = helper.make_node(
        "Conv", ["x16", "W16", "B16"], ["conv16"],
        kernel_shape=[3, 3], pads=[0, 0, 0, 0], strides=[1, 1],
    )
    relu = helper.make_node("Relu", ["conv16"], ["act16"])
    c2 = helper.make_node("Cast", ["act16"], ["y"], to=TensorProto.FLOAT)
    graph = helper.make_graph(
        [c1, conv, relu, c2],
        "probe_fp16_conv",
        [helper.make_tensor_value_info("x", TensorProto.FLOAT, [1, 3, 16, 16])],
        [helper.make_tensor_value_info("y", TensorProto.FLOAT, [1, 4, 14, 14])],
        [numpy_helper.from_array(W16, name="W16"),
         numpy_helper.from_array(B16, name="B16")],
    )
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)])
    model.ir_version = 8
    return model


if __name__ == "__main__":
    save(w600k(), "stub_w600k_r50.onnx")
    save(inswapper(), "stub_inswapper_128.onnx")
    save(fp16_conv(), "probe_fp16_conv.onnx")
    print("total:",
          sum(os.path.getsize(f"{OUT}/{f}") for f in os.listdir(OUT)
              if f.endswith(".onnx")) if False else
          __import__("os").path.getsize(f"{OUT}/stub_w600k_r50.onnx")
          + __import__("os").path.getsize(f"{OUT}/stub_inswapper_128.onnx")
          + __import__("os").path.getsize(f"{OUT}/probe_fp16_conv.onnx"),
          "bytes")
