<script setup lang="ts">
import { computed, nextTick, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { createUser } from '../api/user'

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  created: []
}>()

const formRef = ref<FormInstance>()
const usernameInputRef = ref<{ focus: () => void }>()
const submitting = ref(false)

const form = reactive({
  username: '',
  realName: '',
  mobile: '',
  email: '',
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
}

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => {
    if (!value && submitting.value) {
      return
    }
    emit('update:modelValue', value)
  },
})

function resetForm() {
  form.username = ''
  form.realName = ''
  form.mobile = ''
  form.email = ''
  formRef.value?.clearValidate()
}

function handleOpen() {
  resetForm()
}

function handleOpened() {
  nextTick(() => usernameInputRef.value?.focus())
}

function handleBeforeClose(done: () => void) {
  if (!submitting.value) {
    done()
  }
}

function handleCancel() {
  if (!submitting.value) {
    visible.value = false
  }
}

async function handleSubmit() {
  if (submitting.value) {
    return
  }

  submitting.value = true
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    submitting.value = false
    return
  }

  try {
    const result = await createUser({
      username: form.username,
      realName: form.realName,
      mobile: form.mobile || undefined,
      email: form.email || undefined,
    })

    const message = result.message ?? (result.effective ? '已生效' : '已提交审批')
    if (result.effective) {
      ElMessage.success(message)
    } else {
      ElMessage.info(message)
    }

    submitting.value = false
    emit('update:modelValue', false)
    emit('created')
  } catch {
    // 错误提示由响应拦截器统一处理，保留当前输入供用户修改后重试。
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="新增用户"
    width="min(520px, calc(100vw - 32px))"
    :before-close="handleBeforeClose"
    :close-on-click-modal="!submitting"
    :close-on-press-escape="!submitting"
    :show-close="!submitting"
    @open="handleOpen"
    @opened="handleOpened"
    @closed="resetForm"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
      <el-form-item label="账号" prop="username">
        <el-input ref="usernameInputRef" v-model="form.username" placeholder="请输入账号" />
      </el-form-item>
      <el-form-item label="姓名" prop="realName">
        <el-input v-model="form.realName" placeholder="请输入姓名" />
      </el-form-item>
      <el-form-item label="手机号" prop="mobile">
        <el-input v-model="form.mobile" placeholder="请输入手机号" />
      </el-form-item>
      <el-form-item label="邮箱" prop="email">
        <el-input v-model="form.email" placeholder="请输入邮箱" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button :disabled="submitting" @click="handleCancel">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">创建用户</el-button>
    </template>
  </el-dialog>
</template>
