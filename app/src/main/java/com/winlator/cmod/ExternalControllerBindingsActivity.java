package com.winlator.cmod;

import android.content.Intent;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import androidx.appcompat.app.AppCompatActivity;
import com.winlator.cmod.inputcontrols.Binding;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.ExternalControllerBinding;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.ui.inputcontrols.ExternalControllerBindingItem;
import com.winlator.cmod.ui.inputcontrols.ExternalControllerBindingsCallbacks;
import com.winlator.cmod.ui.inputcontrols.ExternalControllerBindingsComposeHost;
import com.winlator.cmod.ui.inputcontrols.ExternalControllerBindingsState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ExternalControllerBindingsActivity extends AppCompatActivity {
    private ControlsProfile profile;
    private ExternalController controller;
    private final ExternalControllerBindingsState screenState = new ExternalControllerBindingsState();

    private boolean l2WasPressed = false;
    private boolean r2WasPressed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        int profileId = intent.getIntExtra("profile_id", 0);
        profile = InputControlsManager.loadProfile(this, ControlsProfile.getProfileFile(this, profileId));
        String controllerId = intent.getStringExtra("controller_id");

        controller = profile.getController(controllerId);
        if (controller == null) {
            controller = profile.addController(controllerId);
            profile.save();
        }

        refreshBindings();
        List<List<String>> labels = Arrays.asList(
                Arrays.asList(Binding.keyboardBindingLabels()),
                Arrays.asList(Binding.mouseBindingLabels()),
                Arrays.asList(Binding.gamepadBindingLabels()));
        setContentView(ExternalControllerBindingsComposeHost.create(
                this, controller.getName(), screenState, labels,
                new ExternalControllerBindingsCallbacks() {
                    @Override
                    public void onBack() {
                        finish();
                    }

                    @Override
                    public void onRemove(int keyCode) {
                        ExternalControllerBinding item = controller.getControllerBinding(keyCode);
                        if (item == null) return;
                        controller.removeControllerBinding(item);
                        profile.save();
                        refreshBindings();
                    }

                    @Override
                    public void onBindingSelected(int keyCode, int type, int position) {
                        ExternalControllerBinding item = controller.getControllerBinding(keyCode);
                        if (item == null) return;
                        Binding[] values;
                        switch (type) {
                            case 0: values = Binding.keyboardBindingValues(); break;
                            case 1: values = Binding.mouseBindingValues(); break;
                            case 2: values = Binding.gamepadBindingValues(); break;
                            default: return;
                        }
                        if (position < 0 || position >= values.length) return;
                        if (item.getBinding() != values[position]) {
                            item.setBinding(values[position]);
                            profile.save();
                            refreshBindings();
                        }
                    }
                }));
    }

    private void refreshBindings() {
        List<ExternalControllerBindingItem> items = new ArrayList<>();
        for (int i = 0; i < controller.getControllerBindingCount(); i++) {
            ExternalControllerBinding item = controller.getControllerBindingAt(i);
            Binding binding = item.getBinding();
            int type = binding.isMouse() ? 1 : binding.isGamepad() ? 2 : 0;
            items.add(new ExternalControllerBindingItem(
                    item.getKeyCode(), item.toString(), type, binding.toString()));
        }
        screenState.update(items);
    }

    private void updateControllerBinding(int keyCode, Binding binding) {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN)
            return;

        ExternalControllerBinding controllerBinding = controller.getControllerBinding(keyCode);
        if (controllerBinding == null) {
            controllerBinding = new ExternalControllerBinding();
            controllerBinding.setKeyCode(keyCode);
            controllerBinding.setBinding(binding);
            controller.addControllerBinding(controllerBinding);
            profile.save();
            refreshBindings();
        }
        screenState.activate(keyCode);
    }

    private void processJoystickInput() {
        final int[] axes = {
                MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
                MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
                MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y
        };
        final float[] values = {
                controller.state.thumbLX, controller.state.thumbLY,
                controller.state.thumbRX, controller.state.thumbRY,
                controller.state.getDPadX(), controller.state.getDPadY()
        };

        for (int i = 0; i < axes.length; i++) {
            float value = values[i];
            byte sign = Mathf.sign(value);
            if (sign != 0) {
                int keyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], sign);
                updateControllerBinding(keyCode, Binding.NONE);
            }
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        InputDevice device = event.getDevice();
        if (device != null && ExternalController.isGameController(device)
                && controller.updateStateFromMotionEvent(event)) {

            float l2Value = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                    event.getAxisValue(MotionEvent.AXIS_BRAKE));
            float r2Value = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                    event.getAxisValue(MotionEvent.AXIS_GAS));

            boolean l2Pressed = l2Value > 0.8f;
            if (l2Pressed && !l2WasPressed) {
                updateControllerBinding(KeyEvent.KEYCODE_BUTTON_L2, Binding.NONE);
            }
            l2WasPressed = l2Pressed;

            boolean r2Pressed = r2Value > 0.8f;
            if (r2Pressed && !r2WasPressed) {
                updateControllerBinding(KeyEvent.KEYCODE_BUTTON_R2, Binding.NONE);
            }
            r2WasPressed = r2Pressed;

            processJoystickInput();
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isGamepadKeyCode(keyCode)) {
            updateControllerBinding(keyCode, Binding.NONE);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isGamepadKeyCode(keyCode)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private boolean isGamepadKeyCode(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BUTTON_A ||
                keyCode == KeyEvent.KEYCODE_BUTTON_B ||
                keyCode == KeyEvent.KEYCODE_BUTTON_X ||
                keyCode == KeyEvent.KEYCODE_BUTTON_Y ||
                keyCode == KeyEvent.KEYCODE_BUTTON_L1 ||
                keyCode == KeyEvent.KEYCODE_BUTTON_R1 ||
                keyCode == KeyEvent.KEYCODE_BUTTON_L2 ||
                keyCode == KeyEvent.KEYCODE_BUTTON_R2 ||
                keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL ||
                keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR ||
                keyCode == KeyEvent.KEYCODE_BUTTON_START ||
                keyCode == KeyEvent.KEYCODE_BUTTON_SELECT ||
                keyCode == KeyEvent.KEYCODE_BUTTON_MODE ||
                keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER;
    }

}
