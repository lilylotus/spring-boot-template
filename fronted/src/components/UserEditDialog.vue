<script setup lang="ts">
import { computed, nextTick, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { getUser, updateUser } from '../api/user'

const props = defineProps<{
  modelValue: boolean
  userId: number | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  updated: []
}>()

const formRef = ref<FormInstance>()
const realNameInputRef = ref<{ focus: () => void }>()
const loading = ref(false)
const submitting = ref(false)
const dialogOpened = ref(false)
let loadSequence = 0

const form = reactive({
  username: '',
  realName: '',
  mobile: '',
  email: '',
})

const rules: FormRules = {
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
  loadSequence += 1
  loading.value = false
  dialogOpened.value = false
  form.username = ''
  form.realName = ''
  form.mobile = ''
  form.email = ''
  formRef.value?.clearValidate()
}

async function focusFirstEditableField() {
  await nextTick()
  realNameInputRef.value?.focus()
}

async function handleOpen() {
  resetForm()
  const id = props.userId
  if (id === null) {
    visible.value = false
    return
  }

  const currentSequence = ++loadSequence
  loading.value = true
  try {
    const user = await getUser(id)
    if (currentSequence !== loadSequence || !props.modelValue || props.userId !== id) {
      return
    }

    form.username = user.username
    form.realName = user.realName
    form.mobile = user.mobile ?? ''
    form.email = user.email ?? ''
    if (dialogOpened.value) {
      await focusFirstEditableField()
    }
  } catch {
    if (currentSequence === loadSequence && props.modelValue && props.userId === id) {
      visible.value = false
    }
  } finally {
    if (currentSequence === loadSequence) {
      loading.value = false
    }
  }
}

function handleOpened() {
  dialogOpened.value = true
  if (!loading.value) {
    focusFirstEditableField()
  }
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
  if (loading.value || submitting.value) {
    return
  }

  const id = props.userId
  if (id === null) {
    return
  }

  submitting.value = true
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    submitting.value = false
    return
  }

  try {
    const result = await updateUser(id, {
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
    emit('updated')
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
    title="编辑用户"
    width="min(520px, calc(100vw - 32px))"
    :before-close="handleBeforeClose"
    :close-on-click-modal="!submitting"
    :close-on-press-escape="!submitting"
    :show-close="!submitting"
    @open="handleOpen"
    @opened="handleOpened"
    @closed="resetForm"
  >
    <el-form
      ref="formRef"
      v-loading="loading"
      :model="form"
      :rules="rules"
      label-width="80px"
    >
      <el-form-item label="账号" prop="username">
        <el-input v-model="form.username" disabled />
      </el-form-item>
      <el-form-item label="姓名" prop="realName">
        <el-input ref="realNameInputRef" v-model="form.realName" placeholder="请输入姓名" />
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
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="loading"
        @click="handleSubmit"
      >
        提交修改
      </el-button>
    </template>
  </el-dialog>
</template>
