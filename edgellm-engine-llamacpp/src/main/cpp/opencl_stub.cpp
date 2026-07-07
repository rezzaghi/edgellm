// Android vendor OpenCL drivers (Adreno, Mali) don't implement cl_khr_icd,
// so the Khronos ICD loader rejects them. This stub provides the OpenCL API
// symbols ggml-opencl links against and forwards each call to the vendor
// driver, resolved lazily by soname (permitted via uses-native-library).

#define CL_TARGET_OPENCL_VERSION 300

#include <CL/cl.h>
#include <dlfcn.h>

namespace {

void *vendorLib() {
    static void *handle = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    return handle;
}

template <typename Fn>
Fn resolve(const char *name) {
    void *lib = vendorLib();
    return lib ? reinterpret_cast<Fn>(dlsym(lib, name)) : nullptr;
}

} // namespace

#define CL_STUB(ret, name, params, args, fail_expr)                      \
    extern "C" CL_API_ENTRY ret CL_API_CALL name params {                 \
        static auto fn = resolve<ret(CL_API_CALL *) params>(#name);       \
        if (!fn) return fail_expr;                                        \
        return fn args;                                                   \
    }

#define CL_STUB_CREATE(ret, name, params, args)                           \
    extern "C" CL_API_ENTRY ret CL_API_CALL name params {                 \
        static auto fn = resolve<ret(CL_API_CALL *) params>(#name);       \
        if (!fn) {                                                        \
            if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION;         \
            return nullptr;                                               \
        }                                                                 \
        return fn args;                                                   \
    }

CL_STUB(cl_int, clGetPlatformIDs,
        (cl_uint num_entries, cl_platform_id *platforms, cl_uint *num_platforms),
        (num_entries, platforms, num_platforms), CL_INVALID_OPERATION)

CL_STUB(cl_int, clGetPlatformInfo,
        (cl_platform_id platform, cl_platform_info param_name, size_t param_value_size,
         void *param_value, size_t *param_value_size_ret),
        (platform, param_name, param_value_size, param_value, param_value_size_ret),
        CL_INVALID_OPERATION)

CL_STUB(cl_int, clGetDeviceIDs,
        (cl_platform_id platform, cl_device_type device_type, cl_uint num_entries,
         cl_device_id *devices, cl_uint *num_devices),
        (platform, device_type, num_entries, devices, num_devices), CL_INVALID_OPERATION)

CL_STUB(cl_int, clGetDeviceInfo,
        (cl_device_id device, cl_device_info param_name, size_t param_value_size,
         void *param_value, size_t *param_value_size_ret),
        (device, param_name, param_value_size, param_value, param_value_size_ret),
        CL_INVALID_OPERATION)

CL_STUB_CREATE(cl_context, clCreateContext,
        (const cl_context_properties *properties, cl_uint num_devices,
         const cl_device_id *devices,
         void (CL_CALLBACK *pfn_notify)(const char *, const void *, size_t, void *),
         void *user_data, cl_int *errcode_ret),
        (properties, num_devices, devices, pfn_notify, user_data, errcode_ret))

CL_STUB_CREATE(cl_command_queue, clCreateCommandQueue,
        (cl_context context, cl_device_id device,
         cl_command_queue_properties properties, cl_int *errcode_ret),
        (context, device, properties, errcode_ret))

CL_STUB_CREATE(cl_mem, clCreateBuffer,
        (cl_context context, cl_mem_flags flags, size_t size, void *host_ptr,
         cl_int *errcode_ret),
        (context, flags, size, host_ptr, errcode_ret))

CL_STUB_CREATE(cl_mem, clCreateBufferWithProperties,
        (cl_context context, const cl_mem_properties *properties, cl_mem_flags flags,
         size_t size, void *host_ptr, cl_int *errcode_ret),
        (context, properties, flags, size, host_ptr, errcode_ret))

CL_STUB_CREATE(cl_mem, clCreateSubBuffer,
        (cl_mem buffer, cl_mem_flags flags, cl_buffer_create_type buffer_create_type,
         const void *buffer_create_info, cl_int *errcode_ret),
        (buffer, flags, buffer_create_type, buffer_create_info, errcode_ret))

CL_STUB_CREATE(cl_mem, clCreateImage,
        (cl_context context, cl_mem_flags flags, const cl_image_format *image_format,
         const cl_image_desc *image_desc, void *host_ptr, cl_int *errcode_ret),
        (context, flags, image_format, image_desc, host_ptr, errcode_ret))

CL_STUB_CREATE(cl_program, clCreateProgramWithSource,
        (cl_context context, cl_uint count, const char **strings, const size_t *lengths,
         cl_int *errcode_ret),
        (context, count, strings, lengths, errcode_ret))

CL_STUB_CREATE(cl_program, clCreateProgramWithBinary,
        (cl_context context, cl_uint num_devices, const cl_device_id *device_list,
         const size_t *lengths, const unsigned char **binaries, cl_int *binary_status,
         cl_int *errcode_ret),
        (context, num_devices, device_list, lengths, binaries, binary_status, errcode_ret))

CL_STUB(cl_int, clBuildProgram,
        (cl_program program, cl_uint num_devices, const cl_device_id *device_list,
         const char *options,
         void (CL_CALLBACK *pfn_notify)(cl_program, void *), void *user_data),
        (program, num_devices, device_list, options, pfn_notify, user_data),
        CL_INVALID_OPERATION)

CL_STUB(cl_int, clGetProgramBuildInfo,
        (cl_program program, cl_device_id device, cl_program_build_info param_name,
         size_t param_value_size, void *param_value, size_t *param_value_size_ret),
        (program, device, param_name, param_value_size, param_value, param_value_size_ret),
        CL_INVALID_OPERATION)

CL_STUB_CREATE(cl_kernel, clCreateKernel,
        (cl_program program, const char *kernel_name, cl_int *errcode_ret),
        (program, kernel_name, errcode_ret))

CL_STUB(cl_int, clGetKernelInfo,
        (cl_kernel kernel, cl_kernel_info param_name, size_t param_value_size,
         void *param_value, size_t *param_value_size_ret),
        (kernel, param_name, param_value_size, param_value, param_value_size_ret),
        CL_INVALID_OPERATION)

CL_STUB(cl_int, clGetKernelWorkGroupInfo,
        (cl_kernel kernel, cl_device_id device, cl_kernel_work_group_info param_name,
         size_t param_value_size, void *param_value, size_t *param_value_size_ret),
        (kernel, device, param_name, param_value_size, param_value, param_value_size_ret),
        CL_INVALID_OPERATION)

CL_STUB(cl_int, clGetKernelSubGroupInfo,
        (cl_kernel kernel, cl_device_id device, cl_kernel_sub_group_info param_name,
         size_t input_value_size, const void *input_value, size_t param_value_size,
         void *param_value, size_t *param_value_size_ret),
        (kernel, device, param_name, input_value_size, input_value, param_value_size,
         param_value, param_value_size_ret),
        CL_INVALID_OPERATION)

CL_STUB(cl_int, clSetKernelArg,
        (cl_kernel kernel, cl_uint arg_index, size_t arg_size, const void *arg_value),
        (kernel, arg_index, arg_size, arg_value), CL_INVALID_OPERATION)

CL_STUB(cl_int, clEnqueueNDRangeKernel,
        (cl_command_queue command_queue, cl_kernel kernel, cl_uint work_dim,
         const size_t *global_work_offset, const size_t *global_work_size,
         const size_t *local_work_size, cl_uint num_events_in_wait_list,
         const cl_event *event_wait_list, cl_event *event),
        (command_queue, kernel, work_dim, global_work_offset, global_work_size,
         local_work_size, num_events_in_wait_list, event_wait_list, event),
        CL_INVALID_OPERATION)

CL_STUB(cl_int, clEnqueueReadBuffer,
        (cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_read,
         size_t offset, size_t size, void *ptr, cl_uint num_events_in_wait_list,
         const cl_event *event_wait_list, cl_event *event),
        (command_queue, buffer, blocking_read, offset, size, ptr,
         num_events_in_wait_list, event_wait_list, event),
        CL_INVALID_OPERATION)

CL_STUB(cl_int, clEnqueueWriteBuffer,
        (cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_write,
         size_t offset, size_t size, const void *ptr, cl_uint num_events_in_wait_list,
         const cl_event *event_wait_list, cl_event *event),
        (command_queue, buffer, blocking_write, offset, size, ptr,
         num_events_in_wait_list, event_wait_list, event),
        CL_INVALID_OPERATION)

CL_STUB(cl_int, clEnqueueCopyBuffer,
        (cl_command_queue command_queue, cl_mem src_buffer, cl_mem dst_buffer,
         size_t src_offset, size_t dst_offset, size_t size,
         cl_uint num_events_in_wait_list, const cl_event *event_wait_list,
         cl_event *event),
        (command_queue, src_buffer, dst_buffer, src_offset, dst_offset, size,
         num_events_in_wait_list, event_wait_list, event),
        CL_INVALID_OPERATION)

CL_STUB(cl_int, clEnqueueFillBuffer,
        (cl_command_queue command_queue, cl_mem buffer, const void *pattern,
         size_t pattern_size, size_t offset, size_t size,
         cl_uint num_events_in_wait_list, const cl_event *event_wait_list,
         cl_event *event),
        (command_queue, buffer, pattern, pattern_size, offset, size,
         num_events_in_wait_list, event_wait_list, event),
        CL_INVALID_OPERATION)

CL_STUB(cl_int, clEnqueueBarrierWithWaitList,
        (cl_command_queue command_queue, cl_uint num_events_in_wait_list,
         const cl_event *event_wait_list, cl_event *event),
        (command_queue, num_events_in_wait_list, event_wait_list, event),
        CL_INVALID_OPERATION)

CL_STUB(cl_int, clEnqueueMarkerWithWaitList,
        (cl_command_queue command_queue, cl_uint num_events_in_wait_list,
         const cl_event *event_wait_list, cl_event *event),
        (command_queue, num_events_in_wait_list, event_wait_list, event),
        CL_INVALID_OPERATION)

CL_STUB(cl_int, clFinish, (cl_command_queue command_queue), (command_queue),
        CL_INVALID_OPERATION)

CL_STUB(cl_int, clFlush, (cl_command_queue command_queue), (command_queue),
        CL_INVALID_OPERATION)

CL_STUB(cl_int, clWaitForEvents,
        (cl_uint num_events, const cl_event *event_list), (num_events, event_list),
        CL_INVALID_OPERATION)

CL_STUB(cl_int, clGetEventProfilingInfo,
        (cl_event event, cl_profiling_info param_name, size_t param_value_size,
         void *param_value, size_t *param_value_size_ret),
        (event, param_name, param_value_size, param_value, param_value_size_ret),
        CL_INVALID_OPERATION)

CL_STUB(cl_int, clReleaseEvent, (cl_event event), (event), CL_INVALID_OPERATION)

CL_STUB(cl_int, clReleaseMemObject, (cl_mem memobj), (memobj), CL_INVALID_OPERATION)

CL_STUB(cl_int, clReleaseProgram, (cl_program program), (program), CL_INVALID_OPERATION)

CL_STUB(cl_int, clReleaseKernel, (cl_kernel kernel), (kernel), CL_INVALID_OPERATION)

CL_STUB(cl_int, clReleaseCommandQueue, (cl_command_queue command_queue),
        (command_queue), CL_INVALID_OPERATION)

CL_STUB(cl_int, clReleaseContext, (cl_context context), (context), CL_INVALID_OPERATION)

CL_STUB(cl_int, clReleaseDevice, (cl_device_id device), (device), CL_INVALID_OPERATION)

CL_STUB(cl_int, clRetainDevice, (cl_device_id device), (device), CL_INVALID_OPERATION)

CL_STUB(cl_int, clRetainEvent, (cl_event event), (event), CL_INVALID_OPERATION)

CL_STUB(cl_int, clRetainKernel, (cl_kernel kernel), (kernel), CL_INVALID_OPERATION)

CL_STUB(cl_int, clRetainMemObject, (cl_mem memobj), (memobj), CL_INVALID_OPERATION)

CL_STUB(cl_int, clRetainProgram, (cl_program program), (program), CL_INVALID_OPERATION)

CL_STUB(cl_int, clRetainContext, (cl_context context), (context), CL_INVALID_OPERATION)

CL_STUB(cl_int, clRetainCommandQueue, (cl_command_queue command_queue),
        (command_queue), CL_INVALID_OPERATION)
