package com.mocaris.plugin.flutter.json;

import javax.swing.*;
import java.awt.event.*;

public class JsonActionDialog extends JDialog {
    private JPanel contentPane;
    private JButton buttonOK;
    private JButton buttonCancel;
    private JButton formatButton;
    private JCheckBox defValue;
    private JCheckBox extMethod;
    private JTextField dartClassName;
    private JTextArea jsonText;
    private JTextArea jsonContent;

    public JsonActionDialog() {
        setBounds(100, 50, 800, 1000);
        setContentPane(contentPane);
        setModal(true);
        getRootPane().setDefaultButton(buttonOK);

        buttonOK.addActionListener(e -> onOK());

        buttonCancel.addActionListener(e -> onCancel());
        formatButton.addActionListener(e -> format());
        // call onCancel() when cross is clicked
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                onCancel();
            }
        });

        // call onCancel() on ESCAPE
        contentPane.registerKeyboardAction(e -> onCancel(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
    }

    private void format() {
        String jsonContentText = jsonContent.getText();


    }

    private void onOK() {
        setVisible(false);
        dispose();
    }

    private void onCancel() {
        setVisible(false);
        dispose();
    }
}
